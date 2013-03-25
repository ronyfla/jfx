/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism.impl.ps;

import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.Affine3D;
import com.sun.prism.CompositeMode;
import com.sun.prism.PixelFormat;
import com.sun.prism.RTTexture;
import com.sun.prism.RenderTarget;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.camera.PrismCameraImpl;
import com.sun.prism.impl.BaseContext;
import com.sun.prism.impl.BaseGraphics;
import com.sun.prism.impl.VertexBuffer;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.ImagePattern;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.Paint;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.ps.Shader;
import com.sun.prism.ps.ShaderFactory;

/**
 * Maintains resources such as Shaders and GlyphCaches that are intended to
 * be cached on a per-Screen basis, and provides methods that are called by
 * BaseShaderGraphics to validate current state.  The inner State class is
 * used to encapsulate the current and previously validated state (such as
 * texture bindings) so that the validation routines can avoid redundant
 * state changes.  There should be only one BaseShaderContext per Screen,
 * however there may be one or more State instances per BaseShaderContext.
 * <p>
 * A note about State objects... The JOGL architecture creates a GLContext
 * for each GLDrawable (one GLContext per GLDrawable, and one GLDrawable
 * per onscreen window).  Resources such as textures and shaders can be
 * shared between those GLContext instances, but other state (texture bindings,
 * scissor rect, etc) cannot be shared.  Therefore we need to maintain
 * one State instance per GLContext instance, which means there may be more
 * than one State instance per BaseShaderContext.  The currentState variable
 * holds the current State instance corresponding to the current RenderTarget,
 * and is revalidated as part of the updateRenderTarget() method.  The ES2
 * backend will create a new State instance for each window, but the D3D
 * backend is free to create a single State instance that can be shared for
 * the entire Screen.
 */
public abstract class BaseShaderContext extends BaseContext {

    public enum MaskType {
        SOLID          ("Solid"),
        TEXTURE        ("Texture"),
        ALPHA_ONE           ("AlphaOne", true),
        ALPHA_TEXTURE       ("AlphaTexture", true),
        ALPHA_TEXTURE_DIFF  ("AlphaTextureDifference", true),
        FILL_PGRAM     ("FillPgram"),
        DRAW_PGRAM     ("DrawPgram", FILL_PGRAM),
        FILL_CIRCLE    ("FillCircle"),
        DRAW_CIRCLE    ("DrawCircle", FILL_CIRCLE),
        FILL_ELLIPSE   ("FillEllipse"),
        DRAW_ELLIPSE   ("DrawEllipse", FILL_ELLIPSE),
        FILL_ROUNDRECT ("FillRoundRect"),
        DRAW_ROUNDRECT ("DrawRoundRect", FILL_ROUNDRECT),
        DRAW_SEMIROUNDRECT("DrawSemiRoundRect"),
        FILL_CUBICCURVE("FillCubicCurve");

        private String name;
        private MaskType filltype;
        private boolean newPaintStyle;
        private MaskType(String name) {
            this.name = name;
        }
        private MaskType(String name, boolean newstyle) {
            this.name = name;
            this.newPaintStyle = newstyle;
        }
        private MaskType(String name, MaskType filltype) {
            this.name = name;
            this.filltype = filltype;
        }
        public String getName() {
            return name;
        }
        public MaskType getFillType() {
            return filltype;
        }
        public boolean isNewPaintStyle() {
            return newPaintStyle;
        }
    }

    // mask type     4 bits (12 types)
    // paint type    2 bits
    // paint opts    2 bits
    private static final int NUM_STOCK_SHADER_SLOTS =
        MaskType.values().length << 4;
    // TODO: need to dispose these when the context is disposed... (RT-27379)
    private final Shader[] stockShaders = new Shader[NUM_STOCK_SHADER_SLOTS];
    private Shader textureRGBShader;
    private Shader textureYV12Shader;
    private Shader textureFirstLCDShader;
    private Shader textureSecondLCDShader;
    private Shader externalShader;

    private RTTexture lcdBuffer;
    private final ShaderFactory factory;

    private State state;

    protected BaseShaderContext(Screen screen, ShaderFactory factory, VertexBuffer vb) {
        super(screen, factory, vb);
        this.factory = factory;
        init();
    }

    protected void init() {
        state = null;
        if (externalShader != null && !externalShader.isValid()) {
            externalShader.dispose();
            externalShader = null;
        }
        // the rest of the shaders will be re-validated as they are used
    }

    public static class State {
        private Shader lastShader;
        private RenderTarget lastRenderTarget;
        private PrismCameraImpl lastCamera;
        private boolean lastDepthTest;
        private BaseTransform lastTransform = new Affine3D();
        private Rectangle lastClip;
        private CompositeMode lastComp;
        private Texture[] lastTextures = new Texture[4];
        private boolean isXformValid;
        private float lastConst1;
        private float lastConst2;
        private float lastConst3;
        private float lastConst4;
        private float lastConst5;
        private float lastConst6;
    }

    protected abstract State updateRenderTarget(RenderTarget target, PrismCameraImpl camera,
                                                boolean depthTest);

    protected abstract void updateTexture(int texUnit, Texture tex);

    protected abstract void updateShaderTransform(Shader shader,
                                                  BaseTransform xform);

    protected abstract void updateClipRect(Rectangle clipRect);

    protected abstract void updateCompositeMode(CompositeMode mode);

    private static int getStockShaderIndex(MaskType maskType, Paint paint) {
        int paintType;
        int paintOption;
        if (paint == null) {
            paintType = 0;
            paintOption = 0;
        } else {
            paintType = paint.getType().ordinal();
            if (paint.getType().isGradient()) {
                paintOption = ((Gradient)paint).getSpreadMethod();
            } else {
                paintOption = 0;
            }
        }
        return (maskType.ordinal() << 4) | (paintType << 2) | (paintOption << 0);
    }

    private Shader getPaintShader(MaskType maskType, Paint paint) {
        int index = getStockShaderIndex(maskType, paint);
        Shader shader = stockShaders[index];
        if (shader != null && !shader.isValid()) {
            shader.dispose();
            shader = null;
        }
        if (shader == null) {
            String shaderName =
                maskType.getName() + "_" + paint.getType().getName();
            if (paint.getType().isGradient() && !maskType.isNewPaintStyle()) {
                Gradient grad = (Gradient) paint;
                int spreadMethod = grad.getSpreadMethod();
                if (spreadMethod == Gradient.PAD) {
                    shaderName += "_PAD";
                } else if (spreadMethod == Gradient.REFLECT) {
                    shaderName += "_REFLECT";
                } else if (spreadMethod == Gradient.REPEAT) {
                    shaderName += "_REPEAT";
                }
            }
            shader = stockShaders[index] = factory.createStockShader(shaderName);
        }
        return shader;
    }

    private void updatePaintShader(BaseShaderGraphics g, Shader shader,
                                   MaskType maskType, Paint paint,
                                   float bx, float by, float bw, float bh)
    {
        Paint.Type paintType = paint.getType();
        if (paintType == Paint.Type.COLOR || maskType.isNewPaintStyle()) {
            return;
        }

        float rx, ry, rw, rh;
        if (paint.isProportional()) {
            rx = bx; ry = by; rw = bw; rh = bh;
        } else {
            rx = 0f; ry = 0f; rw = 1f; rh = 1f;
        }

        switch (paintType) {
        case LINEAR_GRADIENT:
            PaintHelper.setLinearGradient(g, shader,
                                          (LinearGradient)paint,
                                          rx, ry, rw, rh);
            break;
        case RADIAL_GRADIENT:
            PaintHelper.setRadialGradient(g, shader,
                                          (RadialGradient)paint,
                                          rx, ry, rw, rh);
            break;
        case IMAGE_PATTERN:
            PaintHelper.setImagePattern(g, shader,
                                        (ImagePattern)paint,
                                        rx, ry, rw, rh);
        default:
            break;
        }
    }

    private Shader getTextureRGBShader() {
        if (textureRGBShader != null && !textureRGBShader.isValid()) {
            textureRGBShader.dispose();
            textureRGBShader = null;
        }
        if (textureRGBShader == null) {
            textureRGBShader = factory.createStockShader("Solid_TextureRGB");
        }
        return textureRGBShader;
    }

    private Shader getTextureYV12Shader() {
        if (textureYV12Shader != null && !textureYV12Shader.isValid()) {
            textureYV12Shader.dispose();
            textureYV12Shader = null;
        }
        if (textureYV12Shader == null) {
            textureYV12Shader = factory.createStockShader("Solid_TextureYV12");
        }
        return textureYV12Shader;
    }

    private Shader getTextureFirstPassLCDShader() {
        if (textureFirstLCDShader != null && !textureFirstLCDShader.isValid()) {
            textureFirstLCDShader.dispose();
            textureFirstLCDShader = null;
        }
        if (textureFirstLCDShader == null) {
            textureFirstLCDShader = factory.createStockShader("Solid_TextureFirstPassLCD");
        }
        return textureFirstLCDShader;
    }

    private Shader getTextureSecondPassLCDShader() {
        if (textureSecondLCDShader != null && !textureSecondLCDShader.isValid()) {
            textureSecondLCDShader.dispose();
            textureSecondLCDShader = null;
        }
        if (textureSecondLCDShader == null) {
            textureSecondLCDShader = factory.createStockShader("Solid_TextureSecondPassLCD");
        }
        return textureSecondLCDShader;
    }


    private void updatePerVertexColor(Paint paint, float extraAlpha) {
        if (paint != null && paint.getType() == Paint.Type.COLOR) {
            getVertexBuffer().setPerVertexColor((Color)paint, extraAlpha);
        } else {
            getVertexBuffer().setPerVertexColor(extraAlpha);
        }
    }

    @Override
    public void validatePaintOp(BaseGraphics g, BaseTransform xform,
                                Texture maskTex,
                                float bx, float by, float bw, float bh)
    {
        validatePaintOp((BaseShaderGraphics)g, xform,
                        maskTex, bx, by, bw, bh);
    }

    Shader validatePaintOp(BaseShaderGraphics g, BaseTransform xform,
                           MaskType maskType,
                           float bx, float by, float bw, float bh)
    {
        return validatePaintOp(g, xform, maskType, null, bx, by, bw, bh);
    }

    Shader validatePaintOp(BaseShaderGraphics g, BaseTransform xform,
                           MaskType maskType,
                           float bx, float by, float bw, float bh,
                           float k1, float k2, float k3, float k4, float k5, float k6)
    {
        // this is not ideal, but will have to do for now (tm).
        // various paint primitives use shader parameters, and we have to flush
        // the vertex buffer if those change.  Ideally we would do this in
        // checkState but there is no mechanism to pass this info through.
        if (state.lastConst1 != k1 || state.lastConst2 != k2 ||
            state.lastConst3 != k3 || state.lastConst4 != k4 ||
            state.lastConst5 != k5 || state.lastConst6 != k6)
        {
            flushVertexBuffer();

            state.lastConst1 = k1;
            state.lastConst2 = k2;
            state.lastConst3 = k3;
            state.lastConst4 = k4;
            state.lastConst5 = k5;
            state.lastConst6 = k6;
        }

        return validatePaintOp(g, xform, maskType, null, bx, by, bw, bh);
    }

    Shader validatePaintOp(BaseShaderGraphics g, BaseTransform xform,
                           MaskType maskType, Texture maskTex,
                           float bx, float by, float bw, float bh,
                           float k1, float k2, float k3, float k4, float k5, float k6)
    {
        // this is not ideal, but will have to do for now (tm).
        // various paint primitives use shader parameters, and we have to flush
        // the vertex buffer if those change.  Ideally we would do this in
        // checkState but there is no mechanism to pass this info through.
        if (state.lastConst1 != k1 || state.lastConst2 != k2 ||
            state.lastConst3 != k3 || state.lastConst4 != k4 ||
            state.lastConst5 != k5 || state.lastConst6 != k6)
        {
            flushVertexBuffer();

            state.lastConst1 = k1;
            state.lastConst2 = k2;
            state.lastConst3 = k3;
            state.lastConst4 = k4;
            state.lastConst5 = k5;
            state.lastConst6 = k6;
        }

        return validatePaintOp(g, xform, maskType, maskTex, bx, by, bw, bh);
    }

    Shader validatePaintOp(BaseShaderGraphics g, BaseTransform xform,
                           Texture maskTex,
                           float bx, float by, float bw, float bh)
    {
        return validatePaintOp(g, xform, MaskType.TEXTURE,
                               maskTex, bx, by, bw, bh);
    }

    Shader validatePaintOp(BaseShaderGraphics g, BaseTransform xform,
                           MaskType maskType, Texture maskTex,
                           float bx, float by, float bw, float bh)
    {
        if (maskType == null) {
            throw new InternalError("maskType must be non-null");
        }

        if (externalShader == null) {
            Paint paint = g.getPaint();
            Texture paintTex = null;
            Texture tex0 = null;
            Texture tex1 = null;
            if (paint.getType().isGradient()) {
                // we need to flush here in case the paint shader is staying
                // the same but the paint parameters are changing; we do this
                // unconditionally for now (in theory we could keep track
                // of the last validated paint, and the shape bounds in the
                // case of proportional gradients, but the case where the
                // same paint parameters are used multiple times in a row
                // is so rare that it's not worth optimizing this any further)
                flushVertexBuffer();
                // we have to fetch the texture containing the gradient
                // colors in advance since checkState() is responsible for
                // binding the texture(s)
                if (maskType.isNewPaintStyle()) {
                    paintTex = PaintHelper.getWrapGradientTexture(g);
                } else {
                    paintTex = PaintHelper.getGradientTexture(g, (Gradient)paint);
                }
            } else if (paint.getType() == Paint.Type.IMAGE_PATTERN) {
                ImagePattern texPaint = (ImagePattern)paint;
                ResourceFactory rf = g.getResourceFactory();
                paintTex = rf.getCachedTexture(texPaint.getImage(), Texture.WrapMode.REPEAT);
            }
            // NOTE: We are making assumptions here about which texture
            // corresponds to which texture unit.  In a JSL file the
            // first sampler mentioned will correspond to texture unit 0,
            // the second sampler will correspond to texture unit 1,
            // and so on, and there's currently no way to explicitly
            // associate a sampler with a texture unit in the JSL file.
            // So for now we assume that mask-related samplers are
            // declared before any paint-related samplers in the
            // composed JSL files.
            if (maskTex != null) {
                tex0 = maskTex;
                tex1 = paintTex;
            } else {
                tex0 = paintTex;
                tex1 = null;
            }
            Shader shader = getPaintShader(maskType, paint);
            checkState(g, xform, shader, tex0, tex1);
            updatePaintShader(g, shader, maskType, paint, bx, by, bw, bh);
            updatePerVertexColor(paint, g.getExtraAlpha());
            return shader;
        } else {
            // note that paint is assumed to be a simple Color in this case
            checkState(g, xform, externalShader, maskTex, null);
            updatePerVertexColor(null, g.getExtraAlpha());
            return externalShader;
        }
    }

    @Override
    public void validateTextureOp(BaseGraphics g, BaseTransform xform,
                                  Texture tex0, PixelFormat format)
    {
        validateTextureOp((BaseShaderGraphics)g, xform, tex0, null, format);
    }


    //This function sets the first LCD sample shader.
    public Shader validateLCDOp(BaseShaderGraphics g, BaseTransform xform,
                                Texture tex0, Texture tex1, boolean firstPass,
                                Paint fillColor)
    {
        Shader shader = firstPass ? getTextureFirstPassLCDShader() :
                                    getTextureSecondPassLCDShader();

        checkState(g, xform, shader, tex0, tex1);
        updatePerVertexColor(fillColor, g.getExtraAlpha());
        return shader;
    }

    Shader validateTextureOp(BaseShaderGraphics g, BaseTransform xform,
                             Texture[] textures, PixelFormat format)
    {
        Shader shader = null;

        if (format == PixelFormat.MULTI_YCbCr_420) {
            // must have at least three textures, any more than four are ignored
            if (textures.length < 3) {
                return null;
            }

            if (externalShader == null) {
                shader = getTextureYV12Shader();
            } else {
                shader = externalShader;
            }
        } else { // add more multitexture shaders here
            return null;
        }

        if (null != shader) {
            checkState(g, xform, shader, textures);
            updatePerVertexColor(null, g.getExtraAlpha());
        }
        return shader;
    }

    Shader validateTextureOp(BaseShaderGraphics g, BaseTransform xform,
                             Texture tex0, Texture tex1, PixelFormat format)
    {
        Shader shader;
        if (externalShader == null) {
            switch (format) {
            case INT_ARGB_PRE:
            case BYTE_BGRA_PRE:
            case BYTE_RGB:
            case BYTE_GRAY:
            case BYTE_APPLE_422: // uses GL_RGBA as internal format
                shader = getTextureRGBShader();
                break;
            case MULTI_YCbCr_420: // Must use multitexture method
            case BYTE_ALPHA:
            default:
                throw new InternalError("Pixel format not supported: " + format);
            }
        } else {
            shader = externalShader;
        }
        checkState(g, xform, shader, tex0, tex1);
        updatePerVertexColor(null, g.getExtraAlpha());
        return shader;
    }

    void setExternalShader(BaseShaderGraphics g, Shader shader) {
        // Note that this method is called when the user calls
        // ShaderGraphics.setExternalShader().  We flush any pending
        // operations and synchronously enable the given shader here
        // because the caller (i.e., decora-prism-ps peer) needs to be
        // able to call shader.setConstant() after calling setExternalShader().
        // (In the ES2 backend, setConstant() bottoms out in glUniform(),
        // which can only be called when the program is active, i.e., after
        // shader.enable() is called.  Kind of gross, but that's why the
        // external shader mechanism is setup the way it is currently.)
        // So here we enable the shader just so that the user can update
        // shader constants, and we set the externalShader instance variable.
        // Later in checkState(), we will set the externalShader and
        // update the current transform state "for real".
        flushVertexBuffer();
        if (shader != null) {
            shader.enable();
        }
        externalShader = shader;
    }

    private void checkState(BaseShaderGraphics g,
                            BaseTransform xform,
                            Shader shader,
                            Texture tex0, Texture tex1)
    {
        setRenderTarget(g);

        setTexture(0, tex0);
        setTexture(1, tex1);

        if (shader != state.lastShader) {
            flushVertexBuffer();
            shader.enable();
            state.lastShader = shader;
            // the transform matrix is part of the state of each shader
            // (in ES2 at least), so we need to make sure the transform
            // is updated for the current shader by setting isXformValid=false
            state.isXformValid = false;
        }

        if (!state.isXformValid || !xform.equals(state.lastTransform)) {
            flushVertexBuffer();
            updateShaderTransform(shader, xform);
            state.lastTransform.setTransform(xform);
            state.isXformValid = true;
        }

        Rectangle clip = g.getClipRectNoClone();
        if (clip != state.lastClip) {
            flushVertexBuffer();
            updateClipRect(clip);
            state.lastClip = clip;
        }

        CompositeMode mode = g.getCompositeMode();
        if (mode != state.lastComp) {
            flushVertexBuffer();
            updateCompositeMode(mode);
            state.lastComp = mode;
        }
    }

    private void checkState(BaseShaderGraphics g,
                            BaseTransform xform,
                            Shader shader,
                            Texture[] textures)
    {
        setRenderTarget(g);

        // clamp to 0..4 textures for now, expand on this later if we need to
        int texCount = Math.max(0, Math.min(textures.length, 4));
        for (int index = 0; index < texCount; index++) {
            setTexture(index, textures[index]);
        }

        if (shader != state.lastShader) {
            flushVertexBuffer();
            shader.enable();
            state.lastShader = shader;
            // the transform matrix is part of the state of each shader
            // (in ES2 at least), so we need to make sure the transform
            // is updated for the current shader by setting isXformValid=false
            state.isXformValid = false;
        }

        if (!state.isXformValid || !xform.equals(state.lastTransform)) {
            flushVertexBuffer();
            updateShaderTransform(shader, xform);
            state.lastTransform.setTransform(xform);
            state.isXformValid = true;
        }

        Rectangle clip = g.getClipRectNoClone();
        if (clip != state.lastClip) {
            flushVertexBuffer();
            updateClipRect(clip);
            state.lastClip = clip;
        }

        CompositeMode mode = g.getCompositeMode();
        if (mode != state.lastComp) {
            flushVertexBuffer();
            updateCompositeMode(mode);
            state.lastComp = mode;
        }
    }

    private void setTexture(int texUnit, Texture tex) {
        if (tex != state.lastTextures[texUnit]) {
            flushVertexBuffer();
            updateTexture(texUnit, tex);
            state.lastTextures[texUnit] = tex;
        }
    }

    //Current RenderTarget is the lcdBuffer after this method.
    public void initLCDBuffer(int width, int height) {
        lcdBuffer = factory.createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
    }

    public void disposeLCDBuffer() {
        if (lcdBuffer != null) {
            lcdBuffer.dispose();
            lcdBuffer = null;
        }
    }

    public RTTexture getLCDBuffer() {
        return lcdBuffer;
    }

    //Current RenderTarget is undefined after this method.
    public void validateLCDBuffer(RenderTarget renderTarget) {
        if (lcdBuffer == null ||
                lcdBuffer.getPhysicalWidth() < renderTarget.getPhysicalWidth() ||
                lcdBuffer.getPhysicalHeight() < renderTarget.getPhysicalHeight())
        {
            disposeLCDBuffer();
            initLCDBuffer(renderTarget.getPhysicalWidth(), renderTarget.getPhysicalHeight());
        }
    }

    @Override
    protected void setRenderTarget(RenderTarget target, PrismCameraImpl camera,
                                   boolean depthTest)
    {
        if (state == null ||
            target != state.lastRenderTarget ||
            camera != state.lastCamera ||
            depthTest != state.lastDepthTest)
        {
            flushVertexBuffer();
            state = updateRenderTarget(target, camera, depthTest);
            state.lastRenderTarget = target;
            state.lastCamera = camera;
            state.lastDepthTest = depthTest;
            // the projection matrix is set in updateShaderTransform()
            // because it depends on the dimensions of the destination surface,
            // so if the RenderTarget is changing we force a call to the
            // updateShaderTransform() method by setting isXformValid=false
            state.isXformValid = false;
        }
    }

    @Override
    protected void releaseRenderTarget() {
        // Null out hard references that cause memory leak reported in RT-17304
        if (state != null) {
            state.lastRenderTarget = null;
            for (int i=0; i<state.lastTextures.length; i++) {
                state.lastTextures[i] = null;
            }
        }
    }

    // TODO: 3D - we probablhy need a better way to reset the state
    protected void resetStateWithoutRenderTargetChange() {
        state.isXformValid = false;
        state.lastShader = null;
        for (int i = 0; i != state.lastTextures.length; i++) {
            state.lastTextures[i] = null;
        }
    }
}