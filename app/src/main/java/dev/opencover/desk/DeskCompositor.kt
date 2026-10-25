package dev.opencover.desk

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * An animated desk background. Samsung only plays a video wallpaper on the lock screen, so the desk's wallpaper is
 * a solid [KEY] colour and OpenCover draws the desk onto the monitor itself, putting [video] wherever that colour
 * shows (the desktop between icons and windows). Shadows and the dim taskbar over it come out darker, not the key,
 * so they stay; pure black in an app isn't the key either. [crop] is the part of the video shown, as fractions.
 */
class DeskCompositor(out: Surface, private val w: Int, private val h: Int, private val video: File, private val crop: RectF) {
    /** Where the desk display draws; null if setting up failed (then draw the desk straight to the monitor). */
    var input: Surface? = null; private set

    private val thread = HandlerThread("desk-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var deskTex: SurfaceTexture? = null
    private var videoTex: SurfaceTexture? = null
    private var player: MediaPlayer? = null
    private val deskM = FloatArray(16)
    private val videoM = FloatArray(16)
    private var released = false
    private val tex = IntArray(2) // the desk's texture, the video's

    init {
        val ready = CountDownLatch(1)
        gl.post { runCatching { setUp(out) }.onFailure { Log.w(TAG, "no animated desk background", it); tearDown() }; ready.countDown() }
        ready.await()
    }

    /** Lets go of everything, the monitor's surface included, before returning (it may be drawn on again at once). */
    fun release() {
        val done = CountDownLatch(1)
        gl.post { tearDown(); done.countDown(); thread.quitSafely() }
        done.await()
    }

    private fun setUp(out: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, n, 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], out, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        program = program(VERTEX, FRAGMENT)
        GLES20.glGenTextures(2, tex, 0)
        tex.forEach { t ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
        deskTex = SurfaceTexture(tex[0]).apply { setDefaultBufferSize(w, h); setOnFrameAvailableListener({ draw() }, gl) }
        videoTex = SurfaceTexture(tex[1]).apply { setOnFrameAvailableListener({ draw() }, gl) }
        player = MediaPlayer().apply {
            setDataSource(video.path); setSurface(Surface(videoTex)); isLooping = true; setVolume(0f, 0f); prepare(); start()
        }
        input = Surface(deskTex)
    }

    private fun draw() {
        if (released) return
        runCatching {
            deskTex!!.updateTexImage(); deskTex!!.getTransformMatrix(deskM)
            videoTex!!.updateTexImage(); videoTex!!.getTransformMatrix(videoM)
        }
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "desk"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[1])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "vid"), 1)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "deskM"), 1, false, deskM, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "vidM"), 1, false, videoM, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "crop"), crop.left, crop.top, crop.width(), crop.height())
        val pos = GLES20.glGetAttribLocation(program, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, surface)
    }

    private fun tearDown() {
        released = true
        input?.release(); input = null
        runCatching { player?.release() }; player = null
        deskTex?.release(); videoTex?.release(); deskTex = null; videoTex = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { s ->
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(s) }
        }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
        }
    }

    companion object {
        private const val TAG = "OpenCoverDesk"
        /** The desk wallpaper's colour, replaced by the video: a near-black purple no app draws with. */
        val KEY = android.graphics.Color.rgb(8, 0, 8)

        private val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        private const val VERTEX = """
            attribute vec2 pos;
            varying vec2 uv;
            void main() { uv = pos * 0.5 + 0.5; gl_Position = vec4(pos, 0.0, 1.0); }
        """

        // The key with a little slack for Samsung dimming the wallpaper (dark mode takes off up to ~15%).
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES desk;
            uniform samplerExternalOES vid;
            uniform mat4 deskM;
            uniform mat4 vidM;
            uniform vec4 crop;
            varying vec2 uv;
            void main() {
                vec4 d = texture2D(desk, (deskM * vec4(uv, 0.0, 1.0)).xy);
                vec3 c = d.rgb * 255.0;
                bool key = c.g < 1.5 && abs(c.r - c.b) < 1.5 && c.r > 3.5 && c.r < 12.0;
                if (key) {
                    vec2 v = vec2(crop.x + uv.x * crop.z, 1.0 - (crop.y + (1.0 - uv.y) * crop.w));
                    gl_FragColor = texture2D(vid, (vidM * vec4(v, 0.0, 1.0)).xy);
                } else {
                    gl_FragColor = d;
                }
            }
        """
    }
}
