package com.tfliteflutter.tflite_flutter_helper

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioRecord.OnRecordPositionUpdateListener
import android.util.Log
import androidx.annotation.NonNull // Aunque en Kotlin, @NonNull no es idiomático, se usa NonNull de androidx si la interfaz lo requiere
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.PluginRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
// import java.nio.ShortBuffer // No se usa directamente, asShortBuffer() devuelve ShortBuffer

enum class SoundStreamErrors {
    FailedToRecord,
    FailedToPlay,
    FailedToStop,
    FailedToWriteBuffer,
    Unknown,
}

enum class SoundStreamStatus {
    Unset,
    Initialized,
    Playing,
    Stopped,
}

const val METHOD_CHANNEL_NAME = "com.tfliteflutter.tflite_flutter_helper:methods"

/** TfliteFlutterHelperPlugin */
class TfliteFlutterHelperPlugin : FlutterPlugin,
    MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener,
    ActivityAware {

    private val LOG_TAG = "TfLiteFlutterHelperPlugin"
    private val AUDIO_RECORD_PERMISSION_CODE = 14887
    private val DEFAULT_SAMPLE_RATE = 16000
    private val DEFAULT_BUFFER_SIZE = 8192 // Considerar si este tamaño es fijo o debe ser minBufferSize
    private val DEFAULT_PERIOD_FRAMES = 8192 // Considerar si este tamaño es fijo o debe ser minBufferSize

    private lateinit var methodChannel: MethodChannel
    private var currentActivity: Activity? = null
    private var pluginContext: Context? = null
    private var permissionToRecordAudio: Boolean = false
    private var activeResult: Result? = null
    private var debugLogging: Boolean = false

    private val mRecordFormat = AudioFormat.ENCODING_PCM_16BIT
    private var mRecordSampleRate = DEFAULT_SAMPLE_RATE
    private var mRecorderBufferSize = DEFAULT_BUFFER_SIZE
    private var mPeriodFrames = DEFAULT_PERIOD_FRAMES
    private var audioData: ShortArray? = null
    private var mRecorder: AudioRecord? = null
    private var mListener: OnRecordPositionUpdateListener? = null

    private var activityPluginBinding: ActivityPluginBinding? = null


    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        pluginContext = flutterPluginBinding.applicationContext
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
        methodChannel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "hasPermission" -> hasPermission(result)
                "initializeRecorder" -> initializeRecorder(call, result)
                "startRecording" -> startRecording(result)
                "stopRecording" -> stopRecording(result)
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unexpected exception onMethodCall", e)
            result.error(SoundStreamErrors.Unknown.name, "Unexpected error: ${e.message}", e.stackTraceToString())
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        releaseRecorderResources()
        pluginContext = null
    }

    private fun releaseRecorderResources() {
        mListener?.onMarkerReached(null) // Puede ser problemático llamar a estos directamente.
        mListener?.onPeriodicNotification(null) // Es mejor solo desreferenciar.
        mListener = null
        try {
            mRecorder?.stop()
        } catch (e: IllegalStateException) {
            debugLog("Error stopping recorder on detach: ${e.message}")
        }
        mRecorder?.release()
        mRecorder = null
    }

    // --- ActivityAware Callbacks ---
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        activityPluginBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activityPluginBinding?.removeRequestPermissionsResultListener(this)
        activityPluginBinding = null
        currentActivity = null
        // Considerar si se deben liberar recursos del grabador aquí también,
        // o si se debe esperar a onDetachedFromEngine.
        // Si el plugin debe seguir grabando en background, no liberar aquí.
        // Si la grabación está atada a la Activity, sí liberar.
        // Por ahora, se liberan en onDetachedFromEngine
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding) // Re-adjuntar listeners y actividad
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity() // Des-adjuntar listeners y actividad
    }


    // --- Permission Handling ---
    private fun hasRecordPermission(): Boolean {
        // Actualizar el estado de permiso cada vez que se llama,
        // ya que el usuario puede cambiarlo en la configuración.
        val localContext = pluginContext ?: return false // Si no hay contexto, no hay permiso
        permissionToRecordAudio = ContextCompat.checkSelfPermission(localContext,
            Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return permissionToRecordAudio
    }

    private fun hasPermission(result: Result) {
        result.success(hasRecordPermission())
    }

    private fun requestRecordPermission() {
        val localActivity = currentActivity
        if (localActivity != null && !hasRecordPermission()) { // Comprobar de nuevo antes de pedir
            debugLog("Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(localActivity,
                arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_RECORD_PERMISSION_CODE)
        } else if (localActivity == null) {
            debugLog("Cannot request permission, activity is null")
            // Si no hay actividad, no podemos pedir permiso. Esto debería manejarse.
            // Podríamos enviar un error al activeResult si existe.
            val errorResult = activeResult
            activeResult = null // Consumir el activeResult
            errorResult?.error("NO_ACTIVITY", "Cannot request permission, activity is not available.", null)

        }
        // Si ya tiene permiso, no es necesario hacer nada aquí,
        // la lógica de initializeRecorder continuará.
    }

    // ------------- ESTA ES LA FIRMA CORREGIDA -------------
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>, // NO NULABLE
        grantResults: IntArray // NO NULABLE
    ): Boolean {
        when (requestCode) {
            AUDIO_RECORD_PERMISSION_CODE -> {
                permissionToRecordAudio = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                
                debugLog("Permission result: $permissionToRecordAudio")
                // Continuar con la inicialización si había una llamada pendiente
                completeInitializeRecorder() // activeResult será usado aquí
                return true // Indica que este listener manejó la solicitud
            }
        }
        return false // Indica que este listener no manejó esta solicitud específica
    }


    // --- Recorder Logic ---
    private fun initializeRecorder(@NonNull call: MethodCall, @NonNull result: Result) {
        mRecordSampleRate = call.argument<Int>("sampleRate") ?: mRecordSampleRate
        debugLogging = call.argument<Boolean>("showLogs") ?: false

        // Obtener el tamaño mínimo del buffer basado en la tasa de muestreo
        // Esto es más robusto que usar un valor fijo si no se conoce
        val minBufferSize = AudioRecord.getMinBufferSize(mRecordSampleRate, AudioFormat.CHANNEL_IN_MONO, mRecordFormat)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            result.error("RECORDER_INIT_FAILED", "Invalid sample rate for AudioRecord.", null)
            return
        }
        mPeriodFrames = minBufferSize // Usar minBufferSize como base para periodFrames
        mRecorderBufferSize = mPeriodFrames * 2 // Un buffer común es 2x el tamaño del periodo
        
        audioData = ShortArray(mPeriodFrames)
        activeResult = result // Guardar el result para usarlo después de la solicitud de permiso

        if (pluginContext == null) {
            result.error("NO_CONTEXT", "Plugin context is not available.", null)
            activeResult = null
            return
        }

        if (hasRecordPermission()) {
            debugLog("Has permission, completing initialization.")
            completeInitializeRecorder()
        } else {
            debugLog("Does not have permission, requesting.")
            requestRecordPermission()
            // La ejecución continuará en onRequestPermissionsResult y luego completeInitializeRecorder
        }
        debugLog("Exiting initializeRecorder (async permission request may be in progress)")
    }

    private fun initSpecificRecorder(): Boolean {
        if (mRecorder?.state == AudioRecord.STATE_INITIALIZED) {
            return true // Ya inicializado
        }
        // Liberar cualquier grabador existente antes de crear uno nuevo
        mRecorder?.release()
        mRecorder = null

        try {
            debugLog("Attempting to init AudioRecord with sampleRate: $mRecordSampleRate, bufferSize: $mRecorderBufferSize")
            mRecorder = AudioRecord(MediaRecorder.AudioSource.MIC, mRecordSampleRate, AudioFormat.CHANNEL_IN_MONO, mRecordFormat, mRecorderBufferSize)
        } catch (e: IllegalArgumentException) {
            debugLog("Failed to create AudioRecord: ${e.message}")
            mRecorder = null // Asegurarse de que mRecorder es null si falla
            return false
        }


        if (mRecorder?.state != AudioRecord.STATE_INITIALIZED) {
            debugLog("AudioRecord initialization failed. State: ${mRecorder?.state}")
            mRecorder?.release()
            mRecorder = null
            return false
        }

        mListener = createRecordListener()
        mRecorder?.positionNotificationPeriod = mPeriodFrames
        mRecorder?.setRecordPositionUpdateListener(mListener)
        debugLog("AudioRecord initialized successfully.")
        return true
    }

    private fun completeInitializeRecorder() {
        val currentActiveResult = activeResult ?: return // Si no hay activeResult, no hay nada que completar
        activeResult = null // Consumir el result

        debugLog("Completing initializeRecorder. Has permission: $permissionToRecordAudio")
        val initResult: HashMap<String, Any> = HashMap()

        if (permissionToRecordAudio) {
            if (initSpecificRecorder()) {
                initResult["isMeteringEnabled"] = true // Este nombre es un poco engañoso, tal vez "isRecorderInitialized"
                initResult["success"] = true
                sendRecorderStatus(SoundStreamStatus.Initialized)
            } else {
                initResult["success"] = false
                currentActiveResult.error("RECORDER_INIT_FAILED", "Failed to initialize AudioRecord instance.", null)
                return // Salir temprano si la inicialización del grabador falla
            }
        } else {
            initResult["success"] = false
            // No enviar error aquí si el permiso fue denegado, ya que es un resultado esperado.
            // El cliente Dart debería comprobar el flag 'success'.
        }
        
        debugLog("Sending result from completeInitializeRecorder: $initResult")
        currentActiveResult.success(initResult)
        debugLog("Exiting completeInitializeRecorder")
    }

    private fun sendEventMethod(name: String, data: Any) {
        val eventData: HashMap<String, Any> = HashMap()
        eventData["name"] = name
        eventData["data"] = data
        methodChannel.invokeMethod("platformEvent", eventData)
    }

    private fun debugLog(msg: String) {
        if (debugLogging) {
            Log.d(LOG_TAG, msg)
        }
    }

    private fun startRecording(result: Result) {
        if (mRecorder == null || mRecorder?.state != AudioRecord.STATE_INITIALIZED) {
            result.error(SoundStreamErrors.FailedToRecord.name, "Recorder not initialized.", null)
            return
        }
        if (mRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            result.success(true) // Ya está grabando
            return
        }

        try {
            mRecorder?.startRecording()
            if (mRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                sendRecorderStatus(SoundStreamStatus.Playing)
                result.success(true)
            } else {
                result.error(SoundStreamErrors.FailedToRecord.name, "Failed to start recording, unknown state.", null)
            }
        } catch (e: IllegalStateException) {
            debugLog("startRecording() failed: ${e.message}")
            result.error(SoundStreamErrors.FailedToRecord.name, "Failed to start recording", e.localizedMessage)
        }
    }

    private fun stopRecording(result: Result) {
         if (mRecorder == null) { // No necesita estar inicializado para intentar detener, solo no ser null
            result.success(true) // No hay grabador, así que ya está "detenido"
            return
        }
        if (mRecorder?.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
            result.success(true) // Ya está detenido
            return
        }

        try {
            mRecorder?.stop()
            sendRecorderStatus(SoundStreamStatus.Stopped)
            result.success(true)
        } catch (e: IllegalStateException) {
            debugLog("stopRecording() failed: ${e.message}")
            result.error(SoundStreamErrors.FailedToStop.name, "Failed to stop recording", e.localizedMessage)
        }
    }

    private fun sendRecorderStatus(status: SoundStreamStatus) {
        sendEventMethod("recorderStatus", status.name)
    }

    private fun createRecordListener(): OnRecordPositionUpdateListener {
        return object : OnRecordPositionUpdateListener {
            // onMarkerReached no se usa comúnmente para streaming continuo,
            // pero si lo necesitas, asegúrate de que el marcador esté configurado.
            override fun onMarkerReached(recorder: AudioRecord?) {
                // recorder?.read(audioData!!, 0, mRecorderBufferSize) // Esto parece incorrecto aquí,
                // mRecorderBufferSize podría ser más grande que audioData.
                // Además, leer en onMarkerReached podría no ser lo que quieres.
                debugLog("Marker reached (not typically used for streaming)")
            }

            override fun onPeriodicNotification(recorder: AudioRecord?) {
                val localRecorder = recorder ?: return
                val localAudioData = audioData ?: return

                val shortsRead = localRecorder.read(localAudioData, 0, mPeriodFrames)
                if (shortsRead > 0) {
                    // Crear un ByteBuffer con el tamaño exacto de los datos leídos (shortsRead * 2 bytes)
                    val byteBuffer = ByteBuffer.allocate(shortsRead * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(localAudioData, 0, shortsRead)
                    sendEventMethod("dataPeriod", byteBuffer.array())
                } else if (shortsRead < 0) {
                    // Hubo un error al leer
                    debugLog("Error reading audio data: $shortsRead")
                    // Podrías querer enviar un evento de error al lado Dart aquí.
                    // methodChannel.invokeMethod("platformError", mapOf("errorCode" to shortsRead, "message" to "Error reading audio data"))
                }
            }
        }
    }
}
