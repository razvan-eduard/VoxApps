package com.voxapps.vision.data

import com.voxapps.nativelibs.NativeLibs
import com.voxapps.vision.BuildConfig

/**
 * Vision's native payload: ONNX Runtime and the OpenCV set the OCR pipeline links against.
 *
 * Everything here is what this app *is* — there is no Vision without OCR — so in `minimal` these
 * ship inside the APK and nothing is fetched at launch. `full` keeps them out and downloads them on
 * the splash, which is what the 30MB IzzyOnDroid limit once required.
 *
 * Order matters: [NativeLibs.loadAll] uses System.load() for fetched files, which needs each
 * dependency loaded first. Chain confirmed via `readelf -d`:
 * core → flann → geometry → imgproc → imgcodecs → {features, ptcloud, stereo} → java5.
 * The last five are OpenCV 5.0+ additions — java5's own NEEDED entries list features/ptcloud/stereo
 * directly, even with calib3d/features2d disabled at build time, which is easy to miss because
 * nothing in this app calls their APIs. OpenCV 4.x's opencv_java4.so had no such dependencies.
 */
object NativeLibManager : NativeLibs(
    tagPrefix = "vision",
    versionName = BuildConfig.VERSION_NAME,
    libs = listOf(
        "libonnxruntime.so",
        "libopencv_core.so",
        "libopencv_flann.so",
        "libopencv_geometry.so",
        "libopencv_imgproc.so",
        "libopencv_imgcodecs.so",
        "libopencv_features.so",
        "libopencv_ptcloud.so",
        "libopencv_stereo.so",
        "libopencv_java5.so"
    ),
    bundled = BuildConfig.DLC_MODE == "minimal"
)
