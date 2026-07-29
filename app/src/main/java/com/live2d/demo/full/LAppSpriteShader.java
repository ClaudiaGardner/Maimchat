package com.live2d.demo.full;

import android.opengl.GLES20;

import com.live2d.sdk.cubism.framework.utils.CubismDebug;

/**
 * スプライト用のシェーダー設定を保持するクラス
 */
public class LAppSpriteShader implements AutoCloseable {
    private static final String VERTEX_SHADER_SOURCE =
        "#version 100\n"
            + "attribute vec3 position;\n"
            + "attribute vec2 uv;\n"
            + "varying vec2 vuv;\n"
            + "void main(void) {\n"
            + "    gl_Position = vec4(position, 1.0);\n"
            + "    vuv = uv;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER_SOURCE =
        "#version 100\n"
            + "precision mediump float;\n"
            + "varying vec2 vuv;\n"
            + "uniform sampler2D texture;\n"
            + "uniform vec4 baseColor;\n"
            + "void main(void) {\n"
            + "    gl_FragColor = texture2D(texture, vuv) * baseColor;\n"
            + "}\n";

    /**
     * コンストラクタ
     */
    public LAppSpriteShader() {
        programId = createShader();
    }

    @Override
    public void close() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId);
        }
    }

    /**
     * シェーダーIDを取得する。
     *
     * @return シェーダーID
     */
    public int getShaderId() {
        return programId;
    }

    /**
     * シェーダーを作成する。
     *
     * @return シェーダーID。正常に作成できなかった場合は0を返す。
     */
    private int createShader() {
        // Keep these tiny shaders in code. The project intentionally does not bundle the separately
        // distributed Cubism sample asset directory, and user-selected backgrounds still need a
        // reliable sprite program.
        int vertexShaderId = compileShader(VERTEX_SHADER_SOURCE, GLES20.GL_VERTEX_SHADER);
        int fragmentShaderId = compileShader(FRAGMENT_SHADER_SOURCE, GLES20.GL_FRAGMENT_SHADER);

        if (vertexShaderId == 0 || fragmentShaderId == 0) {
            if (vertexShaderId != 0) {
                GLES20.glDeleteShader(vertexShaderId);
            }
            if (fragmentShaderId != 0) {
                GLES20.glDeleteShader(fragmentShaderId);
            }
            return 0;
        }

        // プログラムオブジェクトの作成
        int programId = GLES20.glCreateProgram();

        // Programのシェーダーを設定
        GLES20.glAttachShader(programId, vertexShaderId);
        GLES20.glAttachShader(programId, fragmentShaderId);

        GLES20.glLinkProgram(programId);

        // 不要になったシェーダーオブジェクトの削除
        GLES20.glDeleteShader(vertexShaderId);
        GLES20.glDeleteShader(fragmentShaderId);

        int[] status = new int[1];
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == GLES20.GL_FALSE) {
            CubismDebug.cubismLogError("Shader link log: %s", GLES20.glGetProgramInfoLog(programId));
            GLES20.glDeleteProgram(programId);
            return 0;
        }

        GLES20.glUseProgram(programId);
        return programId;
    }

    /**
     * CreateShader内部関数。エラーチェックを行う。
     *
     * @param shaderId シェーダーID
     * @return エラーチェック結果。trueの場合、エラーなし。
     */
    private boolean checkShader(int shaderId) {
        int[] logLength = new int[1];
        GLES20.glGetShaderiv(shaderId, GLES20.GL_INFO_LOG_LENGTH, logLength, 0);

        if (logLength[0] > 0) {
            String log = GLES20.glGetShaderInfoLog(shaderId);
            CubismDebug.cubismLogError("Shader compile log: %s", log);
        }

        int[] status = new int[1];
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, status, 0);

        if (status[0] == GLES20.GL_FALSE) {
            GLES20.glDeleteShader(shaderId);
            return false;
        }

        return true;
    }


    /**
     * シェーダーをコンパイルする。
     * コンパイルに成功したら0を返す。
     *
     * @param source シェーダーソース
     * @param shaderType 作成するシェーダーの種類
     * @return シェーダーID。正常に作成できなかった場合は0を返す。
     */
    private int compileShader(String source, int shaderType) {
        // コンパイル
        int shaderId = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shaderId, source);
        GLES20.glCompileShader(shaderId);

        if (!checkShader(shaderId)) {
            return 0;
        }

        return shaderId;
    }

    private final int programId; // シェーダーID
}
