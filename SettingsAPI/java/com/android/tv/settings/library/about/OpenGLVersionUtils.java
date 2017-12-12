/*
 * Copyright (C) 2019 The Android-x86 Open Source Project
 * Copyright (C) 2026 BlissLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tv.settings.library.about;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class OpenGLVersionUtils {

    private static final String LOG_TAG = "OpenGLVersionUtils";

    public static String getOpenGLVersion() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLSurface eglSurface = null;
        EGLContext eglContext = null;

        // Initialize display
        EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
            Log.w(LOG_TAG, "eglGetDisplay failed");
            return "Unknown";
        }
        
        int[] iparam = new int[2];
        if (!egl.eglInitialize(eglDisplay, iparam)) {
            Log.w(LOG_TAG, "eglInitialize failed");
            return "Unknown";
        }

        // Choose config
        EGLConfig[] eglConfigs = new EGLConfig[1];
        final int[] configSpec = { EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE };
        if (egl.eglChooseConfig(eglDisplay, configSpec, eglConfigs, 1, iparam) && iparam[0] > 0) {
            // create surface
            SurfaceTexture surfaceTexture = new SurfaceTexture(0);
            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfigs[0], surfaceTexture, null);
            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                Log.w(LOG_TAG, "eglCreateWindowSurface failed");
            } else {
                // Create context
                final int[] attribList = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
                eglContext = egl.eglCreateContext(
                        eglDisplay, eglConfigs[0], EGL10.EGL_NO_CONTEXT, attribList);
                if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
                    Log.w(LOG_TAG, "eglCreateContext failed");
                }

                // Bind context
                if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    Log.w(LOG_TAG, "eglMakeCurrent failed");
                }
            }
        } else {
            Log.w(LOG_TAG, "eglChooseConfig failed");
        }

        String result = "GL Vendor: " + GLES20.glGetString(GLES20.GL_VENDOR) + "\n" +
                        "GL Renderer: " + GLES20.glGetString(GLES20.GL_RENDERER) + "\n" +
                        "GL Version: " + GLES20.glGetString(GLES20.GL_VERSION);

        if (eglContext != null) {
            egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            egl.eglDestroyContext(eglDisplay, eglContext);
            egl.eglDestroySurface(eglDisplay, eglSurface);
        }
        
        return result;
    }
}
