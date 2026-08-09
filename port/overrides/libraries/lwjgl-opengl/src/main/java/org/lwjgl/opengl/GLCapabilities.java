package org.lwjgl.opengl;

/**
 * WebGL2 capability set used by the original Minecraft renderer.
 *
 * <p>Desktop-only extension flags are deliberately disabled so Blaze3D uses
 * the OpenGL 3.x fallback paths that map directly to WebGL2.</p>
 */
public final class GLCapabilities {
    public final boolean OpenGL30 = true;
    public final boolean OpenGL31 = true;
    public final boolean OpenGL32 = true;
    public final boolean OpenGL33 = true;

    public final boolean GL_ARB_buffer_storage = false;
    public final boolean GL_ARB_debug_output = false;
    public final boolean GL_ARB_direct_state_access = false;
    public final boolean GL_ARB_vertex_attrib_binding = false;
    public final boolean GL_EXT_debug_label = false;
    public final boolean GL_EXT_texture_filter_anisotropic = false;
    public final boolean GL_KHR_debug = false;
}
