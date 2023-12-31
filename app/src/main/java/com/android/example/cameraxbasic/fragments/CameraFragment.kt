/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.example.cameraxbasic.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.util.Consumer
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.layout.WindowInfoTracker
import com.android.example.cameraxbasic.CameraCapability
import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.KEY_EVENT_EXTRA
import com.android.example.cameraxbasic.PopupWindow
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.databinding.FragmentCameraBinding
import com.android.example.cameraxbasic.getAspectRatio
import com.android.example.cameraxbasic.getAspectRatioString
import com.android.example.cameraxbasic.getNameString
import com.android.example.cameraxbasic.utils.ANIMATION_FAST_MILLIS
import com.android.example.cameraxbasic.utils.ANIMATION_SLOW_MILLIS
import com.android.example.cameraxbasic.utils.FocusPointDrawable
import com.android.example.cameraxbasic.utils.MediaStoreUtils
import com.android.example.cameraxbasic.utils.simulateClick
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var broadcastManager: LocalBroadcastManager

    private lateinit var mediaStoreUtils: MediaStoreUtils

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent
    private val captureLiveStatus = MutableLiveData<String>()

    // Camera UI  states and inputs
    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
        RECOVERY    // For future use.
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowInfoTracker: WindowInfoTracker
    private var cameraInfo: CameraInfo? = null
    private var cameraControl: CameraControl? = null
    private var resolutionList = listOf<String>()
    private var aspectRatioList = listOf<String>()
    private var selectorList = listOf<String>()
    private var qualityIndex = 0
    private var aspectRatioIndex = 0
    private var selectorIndex = 0
    private var tabIndex = 0
    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()

        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    private fun setGalleryThumbnail(filename: String) {
        // Run the operations in the view's thread
        cameraUiContainerBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {
                // Remove thumbnail padding
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                // Load thumbnail into circular button using Glide
                Glide.with(photoViewButton)
                    .load(filename)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        // Initialize WindowManager to retrieve display metrics
        windowInfoTracker = WindowInfoTracker.getOrCreate(view.context)

        // Initialize MediaStoreUtils for fetching this app's images
        mediaStoreUtils = MediaStoreUtils(requireContext())

        captureLiveStatus.observe(viewLifecycleOwner) {
            cameraUiContainerBinding?.captureStatus?.apply {
                post { text = it }
            }
        }
        captureLiveStatus.value = "Idle"

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()
            // Set up the camera and its use cases
            lifecycleScope.launch {
                val listSelectorDeferred = async(Dispatchers.IO) {
                    getListSolutionAndAspectRatio()
                }
                listSelectorDeferred.await().run {
                    setUpCamera()
                }
            }
        }
    }

    /**
     * Retrieve the asked camera's type(lens facing type). In this sample, only 2 types:
     *   idx is even number:  CameraSelector.LENS_FACING_BACK
     *          odd number:   CameraSelector.LENS_FACING_FRONT
     */
    private fun getCameraSelector(idx: Int): CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG, "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }

    data class CameraCapability(val camSelector: CameraSelector, val qualities: List<Quality>)

    private fun getListSolutionAndAspectRatio() {

        resolutionList = getOutputSizes(getCameraId().toString(), ImageFormat.JPEG).map {
            it.width.toString() + "x" + it.height.toString()
        }
        aspectRatioList = getSupportedAspectRatios(getCameraId().toString(), ImageFormat.JPEG).map {
            it.toString()
        }

        lifecycleScope.launch {

            val listSelectorDeferred = async(Dispatchers.IO) {
                getListSelector()
            }
            listSelectorDeferred.await().run {
                selectorList = cameraCapabilities[lensFacing].qualities.map {
                    it.getNameString()
                }
            }

            updateUiResolutionAndAspectRatio()

            Log.d("Hien", "getListSolutionAndAspectRatio: ${Thread.currentThread().name}")

        }
    }

    private suspend fun getListSelector() {

        val provider = ProcessCameraProvider.getInstance(requireContext()).await()
        withContext(Dispatchers.Main) {
            provider.unbindAll()
            for (camSelector in arrayOf(
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.DEFAULT_FRONT_CAMERA
            )) {
                try {
                    // just get the camera.cameraInfo to query capabilities
                    // we are not binding anything here.
                    if (provider.hasCamera(camSelector)) {
                        val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                        QualitySelector
                            .getSupportedQualities(camera.cameraInfo)
                            .filter { quality ->
                                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                    .contains(quality)
                            }.also {
                                cameraCapabilities.add(CameraCapability(camSelector, it))
                            }
                    }
                } catch (exc: java.lang.Exception) {
                    Log.e(TAG, "Camera Face $camSelector is not supported")
                }

            }
        }

    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(requireActivity()).await()

        // Select lensFacing depending on the available cameras
        lensFacing = when {
            hasBackCamera() -> CameraSelector.LENS_FACING_BACK
            hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
            else -> throw IllegalStateException("Back and front camera are unavailable")
        }

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        // Build and bind the camera use cases
        bindCameraUseCases()
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireActivity())
        cameraProviderFuture.addListener({
            val quality = cameraCapabilities[lensFacing].qualities[selectorIndex]
            val qualitySelector = QualitySelector.from(quality)

            val rotation = fragmentCameraBinding.viewFinder.display.rotation
            // CameraProvider
            val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

            // CameraSelector
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()


            val selectedResolutionStr: String = resolutionList[qualityIndex]
            val dimensions = selectedResolutionStr.split("x".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
            val width = dimensions[0].toInt()
            val height = dimensions[1].toInt()
            val size = Size(width, height)

            val selectedAspectRatioStr: String = aspectRatioList[aspectRatioIndex]

            val dimensionsRatio =
                selectedAspectRatioStr.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            val ratioWidth = dimensionsRatio[0].toInt()
            val ratioHeight = dimensionsRatio[1].toInt()
            val dimensionRationStr = "V,${ratioHeight}:${ratioWidth}"

            fragmentCameraBinding.viewFinder.updateLayoutParams<ConstraintLayout.LayoutParams> {
//            dimensionRatio = dimensionRationStr
                val orientation = requireActivity().resources.configuration.orientation
                dimensionRatio = quality.getAspectRatioString(
                    quality,
                    (orientation == Configuration.ORIENTATION_PORTRAIT)
                )
            }
            Log.d("Hien", "bindCameraUseCases: quality: $quality tabIndex: $tabIndex - lensFacing: $lensFacing - ${aspectRatioList[aspectRatioIndex]} - ${cameraCapabilities[lensFacing].qualities[selectorIndex]} - ${resolutionList[qualityIndex]}")
            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
//            .setTargetResolution(size)
//            .setTargetAspectRatio(aspectRatio(ratioWidth, ratioHeight))
                .setTargetAspectRatio(quality.getAspectRatio(quality))
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build().apply {
                    setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
                }
            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(size)
//            .setTargetAspectRatio(aspectRatio(ratioWidth, ratioHeight))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // ImageAnalysis
            imageAnalyzer = ImageAnalysis.Builder()
                // We request aspect ratio but no resolution
                .setTargetResolution(size)
//            .setTargetAspectRatio(aspectRatio(ratioWidth, ratioHeight))
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        // Values returned from our analyzer are passed to the attached listener
                        // We log image analysis results here - you should do something useful
                        // instead!
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

            camera?.cameraInfo?.let {
                removeCameraStateObservers(it)
            }

            try {
                // Must unbind the use-cases before rebinding them
                cameraProvider.unbindAll()
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, if (tabIndex == PHOTO) imageCapture else videoCapture
                )
                //camera?.cameraInfo?.let { observeCameraState(it) }
                cameraInfo = camera?.cameraInfo
                cameraControl = camera?.cameraControl
                setTorchStateObserver()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireActivity()))
        // Get screen metrics used to setup camera for full screen resolution
//        val metrics = WindowMetricsCalculator.getOrCreate()
//            .computeCurrentWindowMetrics(requireActivity()).bounds
//        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")
//
//        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
//        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")


    }

    private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.removeObservers(viewLifecycleOwner)
    }

    private fun getOutputSizes(cameraId: String, format: Int): List<Size> {
        val cameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val streamConfigMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return streamConfigMap?.getOutputSizes(format)?.toList() ?: emptyList()
    }

    private fun getSupportedAspectRatios(cameraId: String, format: Int): List<Rational> {
        val cameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val streamConfigMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamConfigMap?.getOutputSizes(format)

        val aspectRatios = mutableListOf<Rational>()

        outputSizes?.forEach { size ->
            val aspectRatio = Rational(size.width, size.height)
            if (!aspectRatios.contains(aspectRatio)) {
                aspectRatios.add(aspectRatio)
            }
        }

        return aspectRatios
    }

    private fun getCameraId(): String? {
        val cameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)

                if (facing == lensFacing) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        return null
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            run {
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // Ask the user to close other camera apps
                        Toast.makeText(
                            context,
                            "CameraState: Pending Open",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.Type.OPENING -> {
                        // Show the Camera UI
                        Toast.makeText(
                            context,
                            "CameraState: Opening",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
                        Toast.makeText(
                            context,
                            "CameraState: Open",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                        Toast.makeText(
                            context,
                            "CameraState: Closing",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                        Toast.makeText(
                            context,
                            "CameraState: Closed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            cameraState.error?.let { error ->
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(
                            context,
                            "Stream config error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(
                            context,
                            "Camera in use",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                        Toast.makeText(
                            context,
                            "Max cameras in use",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(
                            context,
                            "Other recoverable error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(
                            context,
                            "Camera disabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(
                            context,
                            "Fatal error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(
                            context,
                            "Do not disturb mode enabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {
        // Remove previous UI if any
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            fragmentCameraBinding.root,
            true
        )

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestImageFilename()
            thumbnailUri?.let {
                setGalleryThumbnail(it)
            }
        }

        // Listener for button used to capture photo
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            takePhoto()
        }

        // React to user touching the capture button
        cameraUiContainerBinding?.cameraRecordButton?.apply {
            setOnClickListener {
                if (!::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize
                ) {
//                    enableUI(false)  // Our eventListener will turn on the Recording UI.
                    captureVideo()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            cameraUiContainerBinding?.stopButton?.visibility = View.VISIBLE
                        }

                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
        }

        cameraUiContainerBinding?.stopButton?.apply {
            setOnClickListener {
                // stopping: hide it after getting a click before we go to viewing fragment
                cameraUiContainerBinding?.stopButton?.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
                cameraUiContainerBinding?.cameraRecordButton?.setImageResource(R.drawable.ic_start)
            }
            // ensure the stop button is initialized disabled & invisible
            visibility = View.INVISIBLE
            isEnabled = false
        }

        // Setup for button used to switch cameras
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    lensFacing = CameraSelector.LENS_FACING_BACK
                    CameraSelector.LENS_FACING_BACK
                } else {
                    lensFacing = CameraSelector.LENS_FACING_FRONT
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        getListSolutionAndAspectRatio()
                    }
                    Log.d("Hien", "switch: ${Thread.currentThread().name}")
                    updateUiResolutionAndAspectRatio()
                    bindCameraUseCases()
                }
            }
        }

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            lifecycleScope.launch {
                if (mediaStoreUtils.getImages().isNotEmpty()) {
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
                        .navigate(
                            CameraFragmentDirections.actionCameraToGallery(
                                mediaStoreUtils.mediaStoreCollection.toString()
                            )
                        )
                }
            }
        }

        cameraUiContainerBinding?.cameraTorchButton?.setOnClickListener {
            toggleTorch()
        }

        cameraUiContainerBinding?.txtResolution?.setOnClickListener { it ->
            PopupWindow(it, { position ->
                qualityIndex = position
                cameraUiContainerBinding?.txtResolution?.text = resolutionList[position]
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCameraUseCases()
                }
            }, resolutionList).show()
        }

        cameraUiContainerBinding?.txtAspectRatio?.setOnClickListener { it ->
            PopupWindow(it, { position ->
                aspectRatioIndex = position
                cameraUiContainerBinding?.txtAspectRatio?.text = aspectRatioList[position]
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCameraUseCases()
                }
            }, aspectRatioList).show()
        }

        cameraUiContainerBinding?.txtQuality?.setOnClickListener { it ->
            PopupWindow(it, { position ->
                selectorIndex = position
                cameraUiContainerBinding?.txtQuality?.text = selectorList[position]
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCameraUseCases()
                }
            }, selectorList).show()
        }

        val scaleGestureDetector = ScaleGestureDetector(requireActivity(), zoomListener)

        val gestureDetector = GestureDetectorCompat(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val meteringPointFactory = fragmentCameraBinding.viewFinder.meteringPointFactory
                    val focusPoint = meteringPointFactory.createPoint(e.x, e.y)
                    try {
                        val meteringAction = FocusMeteringAction.Builder(focusPoint).build()
                        camera?.cameraControl?.startFocusAndMetering(meteringAction)
                        showFocusPoint(fragmentCameraBinding.focusPoint, e.x, e.y)
                    } catch (e: CameraInfoUnavailableException) {
                        Log.d("ERROR", "cannot access camera", e)
                    }
                    return true
                }
            })

        fragmentCameraBinding.viewFinder.setOnTouchListener { v, event ->
            v.performClick()
            scaleGestureDetector.onTouchEvent(event)
            var didConsume = scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                didConsume = gestureDetector.onTouchEvent(event)
            }
            didConsume
            return@setOnTouchListener true
        }

        cameraUiContainerBinding?.tabLayout?.addOnTabSelectedListener(object :
            TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) {}

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    PHOTO -> {
                        tabIndex = PHOTO
                        cameraUiContainerBinding?.cameraCaptureButton?.visibility = View.VISIBLE
                        cameraUiContainerBinding?.cameraSwitchButton?.visibility = View.VISIBLE
                        cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                        cameraUiContainerBinding?.cameraRecordButton?.visibility = View.GONE
                        bindCameraUseCases()
                    }

                    VIDEO -> {
                        tabIndex = VIDEO
                        cameraUiContainerBinding?.cameraCaptureButton?.visibility = View.GONE
                        cameraUiContainerBinding?.photoViewButton?.visibility = View.GONE
                        cameraUiContainerBinding?.cameraRecordButton?.visibility = View.VISIBLE
                        bindCameraUseCases()
                    }
                }
            }

        })
    }

    private fun captureVideo() {
        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = "CameraX-recording-" +
                SimpleDateFormat(FILENAME, Locale.US)
                    .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        requireActivity(),
                        Manifest.permission.RECORD_AUDIO
                    ) ==
                    PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireActivity()), captureListener)

        Log.i(TAG, "Recording started")
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

//        if (event is VideoRecordEvent.Finalize) {
//            // display the captured video
//            lifecycleScope.launch {
//                navController.navigate(
//                    CaptureFragmentDirections.actionCaptureToVideoViewer(
//                        event.outputResults.outputUri
//                    )
//                )
//            }
//        }
    }

    /**
     * UpdateUI according to CameraX VideoRecordEvent type:
     *   - user starts capture.
     *   - this app disables all UI selections.
     *   - this app enables capture run-time UI (pause/resume/stop).
     *   - user controls recording with run-time UI, eventually tap "stop" to end.
     *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
     *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
     *   - this app starts VideoViewer fragment to view the captured result.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
        else event.getNameString()

        when (event) {
            is VideoRecordEvent.Status -> {
                // placeholder: we update the UI with new status after this when() block,
                // nothing needs to do here.
            }

            is VideoRecordEvent.Start -> {
                showUI(UiState.RECORDING, event.getNameString())
            }

            is VideoRecordEvent.Finalize -> {
                showUI(UiState.FINALIZED, event.getNameString())
            }

            is VideoRecordEvent.Pause -> {
                cameraUiContainerBinding?.cameraRecordButton?.setImageResource(R.drawable.ic_resume)
            }

            is VideoRecordEvent.Resume -> {
                cameraUiContainerBinding?.cameraRecordButton?.setImageResource(R.drawable.ic_pause)
            }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state}: recorded ${size}KB, in ${time}second"
        if (event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        captureLiveStatus.value = text
        Log.i(TAG, "recording event: $text")
    }

    /**
     * initialize UI for recording:
     *  - at recording: hide audio, qualitySelection,change camera UI; enable stop button
     *  - otherwise: show all except the stop button
     */
    private fun showUI(state: UiState, status: String = "idle") {
        cameraUiContainerBinding.let {
            when (state) {
                UiState.IDLE -> {
                    it?.cameraRecordButton?.setImageResource(R.drawable.ic_start)
                    it?.stopButton?.visibility = View.INVISIBLE

                    it?.cameraSwitchButton?.visibility = View.VISIBLE
                    it?.txtResolution?.visibility = View.VISIBLE
                    it?.txtAspectRatio?.visibility = View.VISIBLE
                    it?.txtQuality?.visibility = View.VISIBLE
                }

                UiState.RECORDING -> {
                    cameraUiContainerBinding?.captureStatus?.visibility = View.VISIBLE
                    it?.cameraSwitchButton?.visibility = View.INVISIBLE
                    it?.txtResolution?.visibility = View.INVISIBLE
                    it?.txtAspectRatio?.visibility = View.INVISIBLE
                    it?.txtQuality?.visibility = View.INVISIBLE

                    it?.cameraRecordButton?.setImageResource(R.drawable.ic_pause)
                    it?.cameraRecordButton?.isEnabled = true
                    it?.stopButton?.visibility = View.VISIBLE
                    it?.stopButton?.isEnabled = true
                    cameraUiContainerBinding?.tabLayout?.visibility = View.GONE
                }

                UiState.FINALIZED -> {
                    it?.cameraRecordButton?.setImageResource(R.drawable.ic_start)
                    it?.stopButton?.visibility = View.INVISIBLE
                    cameraUiContainerBinding?.captureStatus?.visibility = View.GONE
                    cameraUiContainerBinding?.txtResolution?.visibility = View.VISIBLE
                    cameraUiContainerBinding?.txtAspectRatio?.visibility = View.VISIBLE
                    cameraUiContainerBinding?.txtQuality?.visibility = View.VISIBLE
                    cameraUiContainerBinding?.tabLayout?.visibility = View.VISIBLE
                    cameraUiContainerBinding?.cameraSwitchButton?.visibility = View.VISIBLE
                }

                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG, errorMsg)
                    return
                }
            }
            it?.captureStatus?.text = status
        }
    }

    private fun takePhoto() {
        imageCapture?.let { imageCapture ->

            // Create time stamped name and MediaStore entry.
            val name = SimpleDateFormat(FILENAME, Locale.US)
                .format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    val appName = requireContext().resources.getString(R.string.app_name)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
                }
            }

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions
                .Builder(
                    requireContext().contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                .build()

            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, ContextCompat.getMainExecutor(requireActivity()), object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // We can only change the foreground Drawable using API level 23+ API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Update the gallery thumbnail with latest picture taken
                            setGalleryThumbnail(savedUri.toString())
                        }

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            // Suppress deprecated Camera usage needed for API level 23 and below
                            @Suppress("DEPRECATION")
                            requireActivity().sendBroadcast(
                                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                // Display flash animation to indicate that photo was captured
                fragmentCameraBinding.root.postDelayed({
                    fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                    fragmentCameraBinding.root.postDelayed(
                        { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    private fun updateUiResolutionAndAspectRatio() {
        aspectRatioIndex = 0
        qualityIndex = 0
        selectorIndex = 0
        cameraUiContainerBinding?.txtAspectRatio?.text = aspectRatioList[aspectRatioIndex]
        cameraUiContainerBinding?.txtResolution?.text = resolutionList[qualityIndex]
        cameraUiContainerBinding?.txtQuality?.text = selectorList[selectorIndex]
    }

    private fun showFocusPoint(view: View?, x: Float, y: Float) {
        val drawable = FocusPointDrawable()
        val strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context?.resources?.displayMetrics
        )
        drawable.setStrokeWidth(strokeWidth)

        val alphaAnimation = SpringAnimation(view, DynamicAnimation.ALPHA, 1f).apply {
            spring.stiffness = SPRING_STIFFNESS
            spring.dampingRatio = SPRING_DAMPING_RATIO

            addEndListener { _, _, _, _ ->
                SpringAnimation(view, DynamicAnimation.ALPHA, 0f)
                    .apply {
                        spring.stiffness = SPRING_STIFFNESS_ALPHA_OUT
                        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    }
                    .start()
            }
        }
        val scaleAnimationX = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            spring.stiffness = SPRING_STIFFNESS
            spring.dampingRatio = SPRING_DAMPING_RATIO
        }
        val scaleAnimationY = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = SPRING_STIFFNESS
            spring.dampingRatio = SPRING_DAMPING_RATIO
        }

        view?.apply {
            background = drawable
            isVisible = true
            translationX = x - width / 2f
            translationY = y - height / 2f
            alpha = 0f
            scaleX = 1.5f
            scaleY = 1.5f
        }

        alphaAnimation.start()
        scaleAnimationX.start()
        scaleAnimationY.start()
    }

    private fun setTorchStateObserver() {
        cameraInfo?.torchState?.observe(requireActivity()) { state ->
            if (state == TorchState.ON) {
                cameraUiContainerBinding?.cameraTorchButton?.setImageDrawable(
                    ContextCompat.getDrawable(
                        requireContext(),
                        R.drawable.ic_flash_off_24
                    )
                )
            } else {
                cameraUiContainerBinding?.cameraTorchButton?.setImageDrawable(
                    ContextCompat.getDrawable(
                        requireActivity(),
                        R.drawable.ic_flash_on_24
                    )
                )
            }
        }
    }

    private fun toggleTorch() {
        if (cameraInfo?.torchState?.value == TorchState.ON) {
            cameraControl?.enableTorch(false)
        } else {
            cameraControl?.enableTorch(true)
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled =
                hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }

    private val zoomListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = (cameraInfo?.zoomState?.value?.zoomRatio ?: 0F) * detector.scaleFactor
            cameraControl?.setZoomRatio(scale)
            return true
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val DEFAULT_QUALITY_IDX = 0
        private const val SPRING_STIFFNESS_ALPHA_OUT = 100f
        private const val SPRING_STIFFNESS = 800f
        private const val SPRING_DAMPING_RATIO = 0.35f
        private const val PHOTO = 0
        private const val VIDEO = 1
    }

    init {
        lifecycleScope.launch {
            whenCreated {
                getListSelector()
            }

        }

    }
}
