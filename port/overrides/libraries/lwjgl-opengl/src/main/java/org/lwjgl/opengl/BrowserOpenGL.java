package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList.WorldListEntry;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.lwjgl.system.MemoryUtil;

/** WebGL2 implementation used by patched LWJGL OpenGL entry points. */
public final class BrowserOpenGL {
    private static final int MAP_WRITE_BIT = 0x0002;
    private static final int MAP_FLUSH_EXPLICIT_BIT = 0x0010;
    private static final int PIXEL_UNPACK_BUFFER = 0x88EC;
    private static final int PIXEL_PACK_BUFFER = 0x88EB;
    private static GLCapabilities capabilities;
    private static final Map<Integer, MappedBuffer> MAPPED_BUFFERS = new HashMap<>();
    private static final ThreadLocal<UniformScratch> UNIFORM_SCRATCH =
            ThreadLocal.withInitial(UniformScratch::new);
    private static int unpackAlignment = 4;
    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;
    private static int packAlignment = 4;
    private static int packRowLength;
    private static int packSkipRows;
    private static int packSkipPixels;
    private static int inventoryWorldRenderFrame;
    private static String inventoryWorldRenderScreen;
    private static int nextSyntheticQuery = 1;

    @JSFunctor
    private interface MappedBufferReleaseCallback extends JSObject {
        void run();
    }

    private BrowserOpenGL() {
    }

    /** Stores the capabilities for the browser's single WebGL context. */
    public static void setCapabilities(GLCapabilities value) {
        capabilities = value;
    }

    /** Returns the capabilities for the browser's single WebGL context. */
    public static GLCapabilities getCapabilities() {
        if (capabilities == null) {
            throw new IllegalStateException("There is no WebGL context current in the browser.");
        }
        return capabilities;
    }

    @JSBody(script = """
            const gl=window.__gaiusWebGL;
            if (!gl) throw new Error('WebGL2 context is not initialized');
            if (!window.__gaiusGL) {
              window.__gaiusGL={next:1,textures:new Map(),buffers:new Map(),shaders:new Map(),
                programs:new Map(),framebuffers:new Map(),vaos:new Map(),samplers:new Map(),syncs:new Map(),
                bufferSizes:new Map(),bufferBytes:new Map(),bufferVersions:new Map(),boundBuffers:new Map(),
                bufferShadowTouch:new Map(),bufferShadowClock:0,bufferShadowTotalBytes:0,
                bufferShadowPeakBytes:0,
                shadowRequiredBuffers:new Set(),
                activeTextureUnit:0,textureBindings:new Map(),textureBufferInfo:new Map(),
                textureInfo:new Map(),framebufferColorTextures:new Map(),framebufferColorTextureMisses:new Set(),
                framebufferBindings:{draw:0,read:0},
                colorMask:[true,true,true,true],
                guiDrawDiagnostics:false,guiDrawsRemaining:0,guiCullFaceBatchActive:false,
                guiItemOffscreenScissorDisabled:false,
                enabledCaps:new Set(),knownCaps:new Set(),
                currentProgram:0,programAttribs:new Map(),programVersion:0,drawProgramGeneration:1,
                currentVaoId:0,vaoEmu:new Map(),alignedAttribCache:new Map(),shiftedIndexCache:new Map(),
                alignedAttribCacheKeys:new Map(),shiftedIndexCacheKeys:new Map(),
                alignedAttribCacheTotalBytes:0,alignedAttribCachePeakBytes:0,
                shiftedIndexCacheTotalBytes:0,shiftedIndexCachePeakBytes:0,
                shiftedIndexCreatedThisFrame:0,shiftedIndexCreatedFrameHighWater:0};
              window.__gaiusGL.registerBufferCacheKey=function(index,buffer,key) {
                const id=buffer|0;
                let keys=index.get(id);
                if (!keys) {
                  keys=new Set();
                  index.set(id,keys);
                }
                keys.add(key);
              };
              window.__gaiusGL.forgetBufferCacheKey=function(index,buffer,key) {
                const id=buffer|0;
                const keys=index.get(id);
                if (!keys) return;
                keys.delete(key);
                if (!keys.size) index.delete(id);
              };
              window.__gaiusGL.updateBufferShadowTelemetry=function() {
                const live=Math.max(0,Number(this.bufferShadowTotalBytes)||0);
                this.bufferShadowTotalBytes=live;
                this.bufferShadowPeakBytes=Math.max(Number(this.bufferShadowPeakBytes)||0,live);
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.bufferShadowLiveBytes=live;
                stats.bufferShadowPeakBytes=this.bufferShadowPeakBytes;
                stats.bufferShadowBudgetBytes=this.maxTotalBufferShadowBytes();
              };
              window.__gaiusGL.updateShiftedIndexTelemetry=function() {
                const live=Math.max(0,Number(this.shiftedIndexCacheTotalBytes)||0);
                this.shiftedIndexCacheTotalBytes=live;
                this.shiftedIndexCachePeakBytes=Math.max(
                  Number(this.shiftedIndexCachePeakBytes)||0,live);
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.baseVertexIndexLiveBytes=live;
                stats.baseVertexIndexPeakBytes=this.shiftedIndexCachePeakBytes;
                stats.baseVertexIndexBudgetBytes=this.maxShiftedIndexCacheBytes();
              };
              window.__gaiusGL.updateAlignedAttribTelemetry=function() {
                const live=Math.max(0,Number(this.alignedAttribCacheTotalBytes)||0);
                this.alignedAttribCacheTotalBytes=live;
                this.alignedAttribCachePeakBytes=Math.max(
                  Number(this.alignedAttribCachePeakBytes)||0,live);
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.alignedAttribLiveBytes=live;
                stats.alignedAttribPeakBytes=this.alignedAttribCachePeakBytes;
                stats.alignedAttribBudgetBytes=this.maxAlignedAttribCacheBytes();
              };
              window.__gaiusGL.trackAlignedAttribEntryVao=function(entry,vao) {
                if (!entry || !vao) return;
                const byLocation=vao.alignedAttribByLocation
                  || (vao.alignedAttribByLocation=new Map());
                const replaced=new Set();
                const layouts=entry.layouts || [];
                for (let i=0;i<layouts.length;i++) {
                  const location=layouts[i].index|0;
                  const previous=byLocation.get(location);
                  if (previous && previous!==entry) replaced.add(previous);
                  byLocation.set(location,entry);
                }
                const refs=entry.vaoRefs || (entry.vaoRefs=new Set());
                refs.add(vao);
                const entries=vao.alignedAttribEntries || (vao.alignedAttribEntries=new Set());
                entries.add(entry);
                replaced.forEach(function(previous) {
                  let retained=false;
                  byLocation.forEach(function(candidate) {
                    if (candidate===previous) retained=true;
                  });
                  if (retained) return;
                  entries.delete(previous);
                  if (previous.vaoRefs) previous.vaoRefs.delete(vao);
                });
              };
              window.__gaiusGL.releaseVaoAlignedAttribRefs=function(vao) {
                if (!vao) return;
                const entries=vao.alignedAttribEntries;
                if (entries && entries.size) {
                  entries.forEach(function(entry) {
                    if (entry && entry.vaoRefs) entry.vaoRefs.delete(vao);
                  });
                  entries.clear();
                }
                if (vao.alignedAttribByLocation) vao.alignedAttribByLocation.clear();
              };
              window.__gaiusGL.detachAlignedAttribEntry=function(entry) {
                if (!entry || !entry.vaoRefs) return;
                entry.vaoRefs.forEach(function(vao) {
                  if (!vao) return;
                  if (vao.alignedAttribEntries) vao.alignedAttribEntries.delete(entry);
                  if (vao.alignedAttribByLocation) {
                    const stale=[];
                    vao.alignedAttribByLocation.forEach(function(candidate,location) {
                      if (candidate===entry) stale.push(location);
                    });
                    for (let i=0;i<stale.length;i++) {
                      vao.alignedAttribByLocation.delete(stale[i]);
                    }
                  }
                  vao.alignedAttribVersion=-1;
                  vao.alignedAttribProgram=-1;
                  vao.alignedAttribGlobalVersion=-1;
                  vao.alignedAttribInvalidated=true;
                  vao.drawReadyGeneration=-1;
                });
                entry.vaoRefs.clear();
              };
              window.__gaiusGL.trackShiftedIndexEntryVao=function(entry,vao) {
                if (!entry || !vao) return;
                const refs=entry.vaoRefs || (entry.vaoRefs=new Set());
                refs.add(vao);
                const entries=vao.shiftedIndexEntries || (vao.shiftedIndexEntries=new Set());
                entries.add(entry);
              };
              window.__gaiusGL.releaseVaoShiftedIndexRefs=function(vao) {
                if (!vao) return;
                const entries=vao.shiftedIndexEntries;
                if (entries && entries.size) {
                  entries.forEach(function(entry) {
                    if (entry && entry.vaoRefs) entry.vaoRefs.delete(vao);
                  });
                  entries.clear();
                }
                vao.shiftedIndexLast=null;
                if (vao.shiftedIndexFastCache) vao.shiftedIndexFastCache.clear();
              };
              window.__gaiusGL.detachShiftedIndexEntry=function(entry) {
                if (!entry || !entry.vaoRefs) return;
                entry.vaoRefs.forEach(function(vao) {
                  if (!vao) return;
                  if (vao.shiftedIndexEntries) vao.shiftedIndexEntries.delete(entry);
                  if (vao.shiftedIndexLast===entry) vao.shiftedIndexLast=null;
                  const fast=vao.shiftedIndexFastCache;
                  if (!fast || !fast.size) return;
                  const stale=[];
                  fast.forEach(function(candidate,key) {
                    if (candidate===entry) stale.push(key);
                  });
                  for (let i=0;i<stale.length;i++) fast.delete(stale[i]);
                });
                entry.vaoRefs.clear();
              };
              window.__gaiusGL.deleteShiftedIndexEntry=function(key,evicted) {
                const entry=this.shiftedIndexCache.get(key);
                if (!entry) return 0;
                entry.deleted=true;
                this.detachShiftedIndexEntry(entry);
                if (entry.buffer) {
                  this.forgetPhysicalElementBuffer(entry.buffer);
                  try { window.__gaiusWebGL.deleteBuffer(entry.buffer); } catch (ignored) {}
                }
                this.shiftedIndexCache.delete(key);
                this.forgetBufferCacheKey(
                  this.shiftedIndexCacheKeys,entry.element|0,key);
                const bytes=Math.max(0,Number(entry.bytes)||0);
                this.shiftedIndexCacheTotalBytes=Math.max(
                  0,(Number(this.shiftedIndexCacheTotalBytes)||0)-bytes);
                this.updateShiftedIndexTelemetry();
                if (evicted) {
                  const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.baseVertexIndexEvictions=(stats.baseVertexIndexEvictions||0)+1;
                  stats.baseVertexIndexEvictedBytes=(stats.baseVertexIndexEvictedBytes||0)+bytes;
                }
                return bytes;
              };
              window.__gaiusGL.deleteAlignedAttribEntry=function(key,evicted) {
                const entry=this.alignedAttribCache.get(key);
                if (!entry) return 0;
                entry.deleted=true;
                this.detachAlignedAttribEntry(entry);
                if (entry.buffer) {
                  try { window.__gaiusWebGL.deleteBuffer(entry.buffer); } catch (ignored) {}
                }
                this.alignedAttribCache.delete(key);
                this.forgetBufferCacheKey(
                  this.alignedAttribCacheKeys,entry.source|0,key);
                const bytes=Math.max(0,Number(entry.bytes)||0);
                this.alignedAttribCacheTotalBytes=Math.max(
                  0,(Number(this.alignedAttribCacheTotalBytes)||0)-bytes);
                this.updateAlignedAttribTelemetry();
                if (evicted) {
                  const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.alignedAttribEvictions=(stats.alignedAttribEvictions||0)+1;
                  stats.alignedAttribEvictedBytes=(stats.alignedAttribEvictedBytes||0)+bytes;
                }
                return bytes;
              };
              window.__gaiusGL.touchAlignedAttribEntry=function(entry) {
                if (!entry || !entry.cacheKey) return;
                const key=entry.cacheKey;
                if (this.alignedAttribCache.get(key)!==entry) return;
                this.alignedAttribCache.delete(key);
                this.alignedAttribCache.set(key,entry);
              };
              window.__gaiusGL.trimAlignedAttribCache=function(incomingBytes) {
                const incoming=Math.max(0,Number(incomingBytes)||0);
                const limit=this.maxAlignedAttribCacheBytes();
                if (incoming>limit) return false;
                while ((Number(this.alignedAttribCacheTotalBytes)||0)+incoming>limit
                    && this.alignedAttribCache.size) {
                  const oldestKey=this.alignedAttribCache.keys().next().value;
                  this.deleteAlignedAttribEntry(oldestKey,true);
                }
                this.updateAlignedAttribTelemetry();
                return (Number(this.alignedAttribCacheTotalBytes)||0)+incoming<=limit;
              };
              window.__gaiusGL.dropBufferDerivedCaches=function(buffer) {
                const id=buffer|0;
                let keys=this.alignedAttribCacheKeys.get(id);
                if (keys) {
                  const alignedKeys=Array.from(keys);
                  for (let i=0;i<alignedKeys.length;i++) {
                    this.deleteAlignedAttribEntry(alignedKeys[i],false);
                  }
                  this.alignedAttribCacheKeys.delete(id);
                }
                keys=this.shiftedIndexCacheKeys.get(id);
                if (keys) {
                  const shiftedKeys=Array.from(keys);
                  for (let i=0;i<shiftedKeys.length;i++) {
                    this.deleteShiftedIndexEntry(shiftedKeys[i],false);
                  }
                  this.shiftedIndexCacheKeys.delete(id);
                }
              };
              window.__gaiusGL.bumpBufferVersion=function(buffer) {
                if (!buffer) return;
                this.bufferVersions.set(buffer,(this.bufferVersions.get(buffer)||0)+1);
                this.dropBufferDerivedCaches(buffer|0);
              };
              window.__gaiusGL.componentBytes=function(type){type=type|0;return type===0x1400||type===0x1401?1:(type===0x1402||type===0x1403||type===0x140B?2:4);};
              window.__gaiusGL.align=function(value, alignment) {
                const a=Math.max(1,alignment|0);
                return Math.ceil(Number(value)/a)*a;
              };
              window.__gaiusGL.newVaoEmu=function() {
                return {
                  attribBindings:new Map(),
                  attribFormats:new Map(),
                  attribPointers:new Map(),
                  vertexBuffers:new Map(),
                  enabledAttribs:new Set(),
                  attribHasBuffer:new Set(),
                  misalignedAttribs:new Set(),
                  missingEnabledAttribs:new Set(),
                  elementArrayBuffer:0,
                  elementArrayBufferObject:null,
                  actualElementArrayBuffer:null,
                  attribVersion:1,
                  attribTypeVersion:1,
                  alignedAttribVersion:-1,
                  alignedAttribProgram:-1,
                  alignedAttribGlobalVersion:-1,
                  directAttribVersion:-1,
                  directAttribProgram:-1,
                  directAttribGlobalVersion:-1,
                  directAttribDirty:false,
                  drawReadyGeneration:-1,
                  programAttribCache:new Map(),
                  alignedAttribEntries:new Set(),
                  alignedAttribByLocation:new Map(),
                  alignedAttribInvalidated:false,
                  shiftedIndexFastCache:new Map(),
                  shiftedIndexEntries:new Set()
                };
              };
              window.__gaiusGL.bumpDrawProgramGeneration=function() {
                this.drawProgramGeneration=((this.drawProgramGeneration||0)+1)|0;
                if (this.drawProgramGeneration<=0) this.drawProgramGeneration=1;
              };
              window.__gaiusGL.bumpVaoAttribVersion=function(vao) {
                if (!vao) return;
                vao.attribVersion=((vao.attribVersion||0)+1)|0;
                if (vao.attribVersion <= 0) vao.attribVersion=1;
                vao.alignedAttribVersion=-1;
                vao.alignedAttribProgram=-1;
                vao.alignedAttribGlobalVersion=-1;
                vao.directAttribVersion=-1;
                vao.directAttribProgram=-1;
                vao.directAttribGlobalVersion=-1;
                vao.drawReadyGeneration=-1;
              };
              window.__gaiusGL.bumpVaoAttribTypeVersion=function(vao){if(!vao)return;vao.attribTypeVersion=((vao.attribTypeVersion||0)+1)|0;if(vao.attribTypeVersion<=0)vao.attribTypeVersion=1;};
              window.__gaiusGL.setAttribBufferPresence=function(vao, index, hasBuffer) {
                const idx=index|0;
                const present=!!hasBuffer;
                const had=vao.attribHasBuffer.has(idx);
                if (present) {
                  vao.attribHasBuffer.add(idx);
                } else {
                  vao.attribHasBuffer.delete(idx);
                }
                if (vao.enabledAttribs.has(idx) && !present) {
                  vao.missingEnabledAttribs.add(idx);
                } else {
                  vao.missingEnabledAttribs.delete(idx);
                }
                if (had!==present) {
                  this.bumpVaoAttribVersion(vao);
                  this.bumpVaoAttribTypeVersion(vao);
                }
              };
              window.__gaiusGL.setAttribMisaligned=function(vao,index,misaligned){const idx=index|0,had=vao.misalignedAttribs.has(idx);if(misaligned)vao.misalignedAttribs.add(idx);else vao.misalignedAttribs.delete(idx);if(had!==!!misaligned)vao.drawReadyGeneration=-1;};
              window.__gaiusGL.sameAttribPointer=function(a,b){return !!a&&!!b&&(a.index|0)===(b.index|0)&&(a.size|0)===(b.size|0)&&(a.type|0)===(b.type|0)&&!!a.normalized===!!b.normalized&&(a.stride|0)===(b.stride|0)&&Number(a.offset)===Number(b.offset)&&!!a.integer===!!b.integer&&(a.buffer|0)===(b.buffer|0);};
              window.__gaiusGL.isIntegerAttribType=function(type){type=type|0;return type===0x1404||type===0x1405||type===0x8B53||type===0x8B54||type===0x8B55||type===0x8DC6||type===0x8DC7||type===0x8DC8;};
              window.__gaiusGL.recordDrawCall=function() {
                const calls=((this.drawCallsCount|0)+1)|0;
                const windowCalls=((this.drawWindowCallsCount|0)+1)|0;
                this.drawCallsCount=calls;
                this.drawWindowCallsCount=windowCalls;
                if ((calls & 255) !== 0) {
                  return;
                }
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.drawCalls=calls;
                stats.drawWindowCalls=windowCalls;
                const now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                if (!this.drawWindowStartedAt) {
                  this.drawWindowStartedAt=now;
                }
                const elapsed=now-this.drawWindowStartedAt;
                if (elapsed >= 1000) {
                  stats.drawCallsPerSecond=Math.round((windowCalls*1000/elapsed)*10)/10;
                  this.drawWindowCallsCount=0;
                  stats.drawWindowCalls=0;
                  this.drawWindowStartedAt=now;
                }
              };
              window.__gaiusGL.captureGuiDrawState=function() {
                const gl=window.__gaiusWebGL;
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                let viewport=null, scissor=null, colorMask=null, depthMask=null;
                try { viewport=Array.from(gl.getParameter(gl.VIEWPORT) || []); } catch (ignored) {}
                try { scissor=Array.from(gl.getParameter(gl.SCISSOR_BOX) || []); } catch (ignored) {}
                try { colorMask=Array.from(gl.getParameter(gl.COLOR_WRITEMASK) || []); } catch (ignored) {}
                try { depthMask=!!gl.getParameter(gl.DEPTH_WRITEMASK); } catch (ignored) {}
                return {
                  drawFramebuffer:this.framebufferBindings.draw|0,
                  readFramebuffer:this.framebufferBindings.read|0,
                  program:this.currentProgram|0,
                  vao:this.currentVaoId|0,
                  viewport:viewport,
                  scissor:scissor,
                  colorMask:colorMask,
                  trackedColorMask:(this.colorMask || []).slice(0,4),
                  depthMask:depthMask,
                  blend:this.enabledCaps.has(gl.BLEND),
                  depthTest:this.enabledCaps.has(gl.DEPTH_TEST),
                  scissorTest:this.enabledCaps.has(gl.SCISSOR_TEST),
                  cullFace:this.enabledCaps.has(gl.CULL_FACE),
                  activeTextureUnit:this.activeTextureUnit|0,
                  boundTexture2D:this.boundTextureId(gl.TEXTURE_2D),
                  remaining:this.guiDrawsRemaining|0,
                  drawCalls:stats.drawCalls||0,
                  at:Date.now()
                };
              };
              window.__gaiusGL.recordGuiDrawState=function(state,error) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                const entry=Object.assign({},state || {}, { error:error|0, after:Date.now() });
                stats.guiDrawStateLast=entry;
                stats.guiDrawStateSamples=(stats.guiDrawStateSamples||0)+1;
                if ((error|0)!==0) {
                  stats.guiDrawStateErrors=(stats.guiDrawStateErrors||0)+1;
                  stats.guiDrawStateLastError=entry;
                }
                const recent=stats.guiDrawStateRecent || (stats.guiDrawStateRecent=[]);
                recent.push(entry);
                if (recent.length>48) recent.splice(0,recent.length-48);
              };
              window.__gaiusGL.readScalar=function(view, byteOffset, type, normalized) {
                const offset=Number(byteOffset);
                if (!view || !Number.isFinite(offset) || offset < 0 || offset >= view.byteLength) return null;
                switch (type|0) {
                  case 0x1400: {
                    if (offset + 1 > view.byteLength) return null;
                    var value=view.getInt8(offset);
                    return normalized ? Math.max(value / 127, -1) : value;
                  }
                  case 0x1401: {
                    if (offset + 1 > view.byteLength) return null;
                    var value=view.getUint8(offset);
                    return normalized ? value / 255 : value;
                  }
                  case 0x1402: {
                    if (offset + 2 > view.byteLength) return null;
                    var value=view.getInt16(offset,true);
                    return normalized ? Math.max(value / 32767, -1) : value;
                  }
                  case 0x1403: {
                    if (offset + 2 > view.byteLength) return null;
                    var value=view.getUint16(offset,true);
                    return normalized ? value / 65535 : value;
                  }
                  case 0x1404: {
                    if (offset + 4 > view.byteLength) return null;
                    var value=view.getInt32(offset,true);
                    return normalized ? Math.max(value / 2147483647, -1) : value;
                  }
                  case 0x1405: {
                    if (offset + 4 > view.byteLength) return null;
                    var value=view.getUint32(offset,true);
                    return normalized ? value / 4294967295 : value;
                  }
                  case 0x1406: {
                    if (offset + 4 > view.byteLength) return null;
                    return view.getFloat32(offset,true);
                  }
                  case 0x140B: {
                    if (offset + 2 > view.byteLength) return null;
                    const half=view.getUint16(offset,true);
                    const sign=(half & 0x8000) ? -1 : 1;
                    const exponent=(half >> 10) & 0x1F;
                    const fraction=half & 0x03FF;
                    if (exponent === 0) {
                      return sign * Math.pow(2,-14) * (fraction / 1024);
                    }
                    if (exponent === 31) {
                      return fraction ? NaN : sign * Infinity;
                    }
                    return sign * Math.pow(2,exponent-15) * (1 + fraction / 1024);
                  }
                  default:
                    return null;
                }
              };
              window.__gaiusGL.readIndex=function(view, byteOffset, type) {
                const offset=Number(byteOffset);
                if (!view || !Number.isFinite(offset) || offset < 0 || offset >= view.byteLength) return null;
                switch (type|0) {
                  case 0x1401:
                    if (offset + 1 > view.byteLength) return null;
                    return view.getUint8(offset);
                  case 0x1403:
                    if (offset + 2 > view.byteLength) return null;
                    return view.getUint16(offset,true);
                  case 0x1405:
                    if (offset + 4 > view.byteLength) return null;
                    return view.getUint32(offset,true);
                  default:
                    return null;
                }
              };
              window.__gaiusGL.sampleVertexAttrib=function(pointer, effectiveIndices) {
                const bytes=this.bufferBytes.get(pointer.buffer|0);
                const componentBytes=this.componentBytes(pointer.type);
                const stride=(pointer.stride|0) || ((pointer.size|0)*componentBytes);
                const result={
                  location:pointer.index|0,
                  buffer:pointer.buffer|0,
                  size:pointer.size|0,
                  type:pointer.type|0,
                  normalized:!!pointer.normalized,
                  integer:!!pointer.integer,
                  stride:stride|0,
                  offset:Number(pointer.offset),
                  shadowBytes:bytes && bytes.byteLength ? bytes.byteLength|0 : 0,
                  values:[]
                };
                if (!bytes || !bytes.byteLength || !componentBytes || stride <= 0) {
                  result.missingShadow=true;
                  return result;
                }
                const view=new DataView(bytes.buffer,bytes.byteOffset || 0,bytes.byteLength);
                const maxSamples=Math.min(4,effectiveIndices.length);
                for (let i=0;i<maxSamples;i++) {
                  const vertex=Number(effectiveIndices[i]);
                  const baseOffset=Number(pointer.offset) + vertex * stride;
                  const components=[];
                  for (let component=0;component<(pointer.size|0);component++) {
                    components.push(this.readScalar(
                      view,baseOffset + component * componentBytes,pointer.type|0,!!pointer.normalized));
                  }
                  result.values.push({
                    vertex:vertex,
                    byteOffset:baseOffset,
                    components:components
                  });
                }
                return result;
              };
              window.__gaiusGL.sampleGuiDraw=function(mode,count,type,offset,instances,baseVertex) {
                if (!this.guiDrawDiagnostics || (this.guiDrawsRemaining|0)<=0) return;
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                const elementBuffer=vao.elementArrayBuffer|0;
                const indexBytes=this.indexBytes(type);
                const start=Number(offset);
                const length=count|0;
                const base=baseVertex|0;
                const source=this.bufferBytes.get(elementBuffer);
                const entry={
                  mode:mode|0,
                  count:length,
                  type:type|0,
                  offset:start,
                  instances:instances|0,
                  baseVertex:base,
                  program:this.currentProgram|0,
                  vao:this.currentVaoId|0,
                  elementBuffer:elementBuffer,
                  elementShadowBytes:source && source.byteLength ? source.byteLength|0 : 0,
                  remaining:this.guiDrawsRemaining|0,
                  attribs:[],
                  indexSample:[],
                  at:Date.now()
                };
                if (!source || !source.byteLength || !indexBytes || length <= 0
                    || !Number.isFinite(start) || start < 0
                    || start + length * indexBytes > source.byteLength) {
                  entry.indexError='missing-or-out-of-range-index-shadow';
                } else {
                  const view=new DataView(source.buffer,source.byteOffset || 0,source.byteLength);
                  let min=Number.POSITIVE_INFINITY;
                  let max=Number.NEGATIVE_INFINITY;
                  const uniqueEffective=[];
                  const scanCount=Math.min(length,16384);
                  for (let i=0;i<scanCount;i++) {
                    const original=this.readIndex(view,start + i * indexBytes,type|0);
                    if (original === null) break;
                    const effective=Number(original) + base;
                    if (effective < min) min=effective;
                    if (effective > max) max=effective;
                    if (entry.indexSample.length < 12) {
                      entry.indexSample.push({ original:Number(original), effective:effective });
                    }
                    if (uniqueEffective.length < 8 && !uniqueEffective.includes(effective)) {
                      uniqueEffective.push(effective);
                    }
                  }
                  entry.indexMin=Number.isFinite(min) ? min : null;
                  entry.indexMax=Number.isFinite(max) ? max : null;
                  entry.indexScanCount=scanCount;
                  const program=this.currentProgram|0;
                  let active=this.programAttribs.get(program);
                  if (program && !active) {
                    this.refreshProgramAttribs(program);
                    active=this.programAttribs.get(program);
                  }
                  const locations=[];
                  const names=new Map();
                  if (active && active.length) {
                    for (let i=0;i<active.length;i++) {
                      const attrib=active[i];
                      if (!attrib) continue;
                      locations.push(attrib.location|0);
                      names.set(attrib.location|0,String(attrib.name || ''));
                    }
                  } else {
                    vao.enabledAttribs.forEach(function(location) { locations.push(location|0); });
                  }
                  locations.sort(function(a,b) { return (a|0)-(b|0); });
                  for (let i=0;i<locations.length && entry.attribs.length<12;i++) {
                    const location=locations[i]|0;
                    if (!vao.enabledAttribs.has(location)) continue;
                    const pointer=vao.attribPointers.get(location);
                    if (!pointer || !pointer.buffer || !vao.attribHasBuffer.has(location)) {
                      entry.attribs.push({
                        location:location,
                        name:names.get(location) || '',
                        missingPointer:true
                      });
                      continue;
                    }
                    const sample=this.sampleVertexAttrib(pointer,uniqueEffective);
                    sample.name=names.get(location) || '';
                    sample.misaligned=!!(vao.misalignedAttribs && vao.misalignedAttribs.has(location));
                    entry.attribs.push(sample);
                  }
                }
                stats.guiVertexSamples=(stats.guiVertexSamples||0)+1;
                stats.guiVertexSampleLast=entry;
                const recent=stats.guiVertexSampleRecent || (stats.guiVertexSampleRecent=[]);
                recent.push(entry);
                if (recent.length>24) recent.splice(0,recent.length-24);
              };
              window.__gaiusGL.boundTextureId=function(target) {
                const gl=window.__gaiusWebGL;
                const unit=this.activeTextureUnit || 0;
                const keyBase=unit*65536;
                return (this.textureBindings.get(keyBase+((target|0)&65535))
                  || this.textureBindings.get(keyBase+(gl.TEXTURE_2D&65535))
                  || this.textureBindings.get(keyBase+35882)
                  || 0)|0;
              };
              window.__gaiusGL.invalidateGuiItemAtlasBlitCache=function() {};
              window.__gaiusGL.findFramebufferColorTextureId=function(framebuffer) {
                const id=framebuffer|0;
                if (this.framebufferColorTextures.has(id)) return this.framebufferColorTextures.get(id)|0;
                if (this.framebufferColorTextureMisses && this.framebufferColorTextureMisses.has(id)) return 0;
                const gl=window.__gaiusWebGL;
                const drawObject=this.framebuffers.get(id);
                if (!drawObject) return 0;
                const previousRead=this.framebufferBindings.read|0;
                const previousDraw=this.framebufferBindings.draw|0;
                let targetTextureId=0;
                try {
                  gl.bindFramebuffer(gl.FRAMEBUFFER,drawObject);
                  const colorObject=gl.getFramebufferAttachmentParameter(
                    gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                  const textureIterator=this.textures.entries();
                  for (let nextTexture=textureIterator.next(); !nextTexture.done; nextTexture=textureIterator.next()) {
                    const pair=nextTexture.value;
                    const textureId=pair[0];
                    const texture=pair[1];
                    if (texture===colorObject) {
                      targetTextureId=Number(textureId)|0;
                      break;
                    }
                  }
                } catch (ignored) {
                  targetTextureId=0;
                } finally {
                  gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:this.framebuffers.get(previousRead));
                  gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:this.framebuffers.get(previousDraw));
                }
                if (targetTextureId) {
                  this.framebufferColorTextures.set(id,targetTextureId|0);
                } else if (this.framebufferColorTextureMisses) {
                  this.framebufferColorTextureMisses.add(id);
                }
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.framebufferColorTextureFallbacks=(stats.framebufferColorTextureFallbacks||0)+1;
                return targetTextureId|0;
              };
              window.__gaiusGL.isGuiItemOffscreen512Target=function() {
                const drawFramebuffer=this.framebufferBindings.draw|0;
                if (!drawFramebuffer) return false;
                const targetTextureId=this.findFramebufferColorTextureId(drawFramebuffer);
                const targetInfo=targetTextureId ? this.textureInfo.get(targetTextureId) : null;
                return !!(targetInfo && (targetInfo.width|0)===512 && (targetInfo.height|0)===512);
              };
              window.__gaiusGL.restoreGuiItemOffscreenScissor=function(reason) {
                if (!this.guiItemOffscreenScissorDisabled) return;
                const gl=window.__gaiusWebGL;
                gl.enable(gl.SCISSOR_TEST);
                this.guiItemOffscreenScissorDisabled=false;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.offscreen512ScissorBatchRestores=(stats.offscreen512ScissorBatchRestores||0)+1;
                if (reason) stats.offscreen512ScissorBatchRestoreReason=String(reason);
              };
              window.__gaiusGL.recordTextureUpload=function(kind,target,level,x,y,width,height,internalFormat,format,type,pixels) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                const texture=this.boundTextureId(target);
                const byteLength=pixels && pixels.byteLength ? pixels.byteLength|0 : 0;
                stats.textureUploads=(stats.textureUploads||0)+1;
                stats.textureUploadBytes=(stats.textureUploadBytes||0)+byteLength;
                let info=null;
                if (texture) {
                  info=this.textureInfo.get(texture);
                  let publishInfo=false;
                  if (!info) {
                    info={texture:texture|0,version:0};
                    this.textureInfo.set(texture,info);
                    publishInfo=true;
                  }
                  info.version=((info.version||0)+1)|0;
                  info.byteLength=byteLength;
                  if (kind==='texImage2D' && (level|0)===0) {
                    publishInfo=true;
                    if (width>0) info.width=width|0;
                    if (height>0) info.height=height|0;
                    info.internalFormat=internalFormat|0;
                    info.format=format|0;
                    info.type=type|0;
                  }
                  if (publishInfo && stats.textureInfo) stats.textureInfo[String(texture)]=info;
                  this.invalidateGuiItemAtlasBlitCache(texture,'upload');
                }
                if (!this.hotPathTelemetryEnabled) return;
                const entry={kind:String(kind),texture:texture,target:target|0,level:level|0,
                  x:x|0,y:y|0,width:width|0,height:height|0,internalFormat:internalFormat|0,
                  format:format|0,type:type|0,byteLength:byteLength,at:Date.now()};
                stats.textureUploadLast=entry;
                const recent=stats.textureUploadRecent || (stats.textureUploadRecent=[]);
                recent.push(entry);
                if (recent.length>64) recent.splice(0,recent.length-64);
              };
              window.__gaiusGL.recordTextureError=function(kind,target,level,width,height,format,type,pixels,error) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                const entry={
                  kind:String(kind),
                  texture:this.boundTextureId(target),
                  target:target|0,
                  level:level|0,
                  width:width|0,
                  height:height|0,
                  format:format|0,
                  type:type|0,
                  byteLength:pixels && pixels.byteLength ? pixels.byteLength|0 : 0,
                  message:String(error && (error.message || error.name) || error),
                  at:Date.now()
                };
                stats.textureUploadErrors=(stats.textureUploadErrors||0)+1;
                stats.textureUploadErrorLast=entry;
                const recent=stats.textureUploadErrorRecent || (stats.textureUploadErrorRecent=[]);
                recent.push(entry);
                if (recent.length>32) recent.splice(0,recent.length-32);
              };
              window.__gaiusGL.maxSingleBufferShadowBytes=function() {
                const fallback=16 * 1024 * 1024;
                const configured=Number(window.__gaiusMaxSingleBufferShadowBytes);
                if (Number.isFinite(configured) && configured >= 0) return Math.floor(configured);
                return Math.min(fallback,this.maxTotalBufferShadowBytes());
              };
              window.__gaiusGL.maxTotalBufferShadowBytes=function() {
                const fallback=64 * 1024 * 1024;
                const preferred=Number(window.__gaiusBufferShadowBudgetBytes);
                if (Number.isFinite(preferred) && preferred >= 0) return Math.floor(preferred);
                const configured=Number(window.__gaiusMaxTotalBufferShadowBytes);
                if (Number.isFinite(configured) && configured >= 0) return Math.floor(configured);
                return fallback;
              };
              window.__gaiusGL.maxShiftedIndexCacheBytes=function() {
                const fallback=32 * 1024 * 1024;
                const preferred=Number(window.__gaiusBaseVertexDerivedBufferBudgetBytes);
                if (Number.isFinite(preferred) && preferred >= 0) return Math.floor(preferred);
                const configured=Number(window.__gaiusDerivedIndexBufferBudgetBytes);
                if (Number.isFinite(configured) && configured >= 0) return Math.floor(configured);
                return fallback;
              };
              window.__gaiusGL.maxAlignedAttribCacheBytes=function() {
                const fallback=32 * 1024 * 1024;
                const preferred=Number(window.__gaiusAlignedAttribDerivedBufferBudgetBytes);
                if (Number.isFinite(preferred) && preferred >= 0) return Math.floor(preferred);
                const configured=Number(window.__gaiusDerivedAttribBufferBudgetBytes);
                if (Number.isFinite(configured) && configured >= 0) return Math.floor(configured);
                return fallback;
              };
              window.__gaiusGL.deleteBufferShadow=function(buffer) {
                if (!buffer) return 0;
                const existing=this.bufferBytes.get(buffer);
                const bytes=existing && existing.byteLength ? existing.byteLength : 0;
                this.bufferShadowTotalBytes=Math.max(
                  0,(Number(this.bufferShadowTotalBytes)||0)-bytes);
                this.bufferBytes.delete(buffer);
                this.bufferShadowTouch.delete(buffer);
                this.updateBufferShadowTelemetry();
                return bytes;
              };
              window.__gaiusGL.dropBufferShadow=function(buffer, reason) {
                if (!buffer) return false;
                const hadShadow=this.bufferBytes.has(buffer) || this.bufferShadowTouch.has(buffer);
                if (!hadShadow) return false;
                this.deleteBufferShadow(buffer);
                this.bumpBufferVersion(buffer);
                if (this.hotPathTelemetryEnabled) {
                  const skipped=((this.bufferShadowSkippedUnneededCount|0)+1)|0;
                  this.bufferShadowSkippedUnneededCount=skipped;
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.bufferShadowSkippedUnneeded=skipped;
                  if (reason && (skipped===1 || (skipped & 1023)===0)) stats.bufferShadowSkippedUnneededReason=reason;
                }
                return true;
              };
              window.__gaiusGL.markBufferShadowRequired=function(buffer, reason) {
                if (!buffer) return;
                const id=buffer|0;
                if (this.shadowRequiredBuffers.has(id)) return;
                this.shadowRequiredBuffers.add(id);
                const marks=((this.bufferShadowRequiredMarkCount|0)+1)|0;
                this.bufferShadowRequiredMarkCount=marks;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.bufferShadowRequiredMarks=marks;
                if (reason) stats.bufferShadowRequiredLastReason=reason;
              };
              window.__gaiusGL.bufferNeedsArrayShadow=function(buffer){if(!buffer)return false;const id=buffer|0;if(this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id))return true;let n=false;this.vaoEmu.forEach(function(v){if(n||!v||!v.misalignedAttribs||!v.misalignedAttribs.size)return;v.misalignedAttribs.forEach(function(a){const p=v.attribPointers&&v.attribPointers.get(a|0);if(p&&(p.buffer|0)===id)n=true;});});return n;};
              window.__gaiusGL.shouldShadowBufferTarget=function(target, buffer) {
                if (!buffer) return false;
                const gl=window.__gaiusWebGL;
                if (target===gl.ELEMENT_ARRAY_BUFFER) {
                  return this.bufferBytes.has(buffer|0)
                    || !this.hasUsableBaseVertexExtension();
                }
                if (this.shadowRequiredBuffers && this.shadowRequiredBuffers.has(buffer|0)) return true;
                if (target===0x8C2A) return true;
                if (target===gl.COPY_READ_BUFFER || target===gl.COPY_WRITE_BUFFER) return true;
                if (target===gl.ARRAY_BUFFER) return this.bufferNeedsArrayShadow(buffer|0);
                return false;
              };
              window.__gaiusGL.shadowBufferDataForTarget=function(target,buffer,data,size) {
                if (this.shouldShadowBufferTarget(target,buffer)) {
                  this.shadowBufferData(buffer,data,size);
                } else {
                  if (!this.dropBufferShadow(buffer,'target:'+target)) {
                    this.bumpBufferVersion(buffer);
                  }
                }
              };
              window.__gaiusGL.shadowBufferSubDataForTarget=function(target,buffer,offset,data) {
                if (this.shouldShadowBufferTarget(target,buffer)) {
                  this.shadowBufferSubData(buffer,offset,data);
                } else {
                  if (!this.dropBufferShadow(buffer,'target:'+target)) {
                    this.bumpBufferVersion(buffer);
                  }
                }
              };
              window.__gaiusGL.touchBufferShadow=function(buffer, bytes) {
                if (!buffer || !bytes) return;
                this.bufferShadowTouch.delete(buffer);
                this.bufferShadowTouch.set(buffer,++this.bufferShadowClock);
              };
              window.__gaiusGL.evictOldestBufferShadow=function() {
                const oldest=this.bufferShadowTouch.keys().next();
                const oldestKey=oldest.done ? null : oldest.value;
                if (oldestKey===null) return 0;
                const bytes=this.deleteBufferShadow(oldestKey);
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.bufferShadowEvictions=(stats.bufferShadowEvictions||0)+1;
                stats.bufferShadowEvictedBytes=(stats.bufferShadowEvictedBytes||0)+bytes;
                return bytes;
              };
              window.__gaiusGL.reserveBufferShadowBytes=function(buffer,bytes) {
                const limit=this.maxTotalBufferShadowBytes();
                const requested=Math.max(0,Number(bytes)||0);
                this.deleteBufferShadow(buffer);
                if (!Number.isFinite(limit) || requested > limit) return false;
                while ((Number(this.bufferShadowTotalBytes)||0)+requested > limit
                    && this.bufferBytes.size) {
                  if (!this.evictOldestBufferShadow()) break;
                }
                return (Number(this.bufferShadowTotalBytes)||0)+requested <= limit;
              };
              window.__gaiusGL.trimBufferShadows=function() {
                const limit=this.maxTotalBufferShadowBytes();
                while ((Number(this.bufferShadowTotalBytes)||0) > limit
                    && this.bufferBytes.size) {
                  if (!this.evictOldestBufferShadow()) break;
                }
                this.updateBufferShadowTelemetry();
              };
              window.__gaiusGL.noteBufferShadowCopy=function(bytes,startedAt,readback) {
                if (!this.hotPathTelemetryEnabled) return;
                const now=(typeof performance !== 'undefined' && performance.now)
                  ? performance.now() : Date.now();
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.bufferShadowCopyBytes=(stats.bufferShadowCopyBytes||0)+Math.max(0,Number(bytes)||0);
                stats.bufferShadowCopyMs=(stats.bufferShadowCopyMs||0)+Math.max(0,now-startedAt);
                if (readback) {
                  stats.bufferShadowReadbacks=(stats.bufferShadowReadbacks||0)+1;
                  stats.bufferShadowReadbackBytes=(stats.bufferShadowReadbackBytes||0)+Math.max(0,Number(bytes)||0);
                }
              };
              window.__gaiusGL.readBufferShadow=function(buffer,size) {
                const id=buffer|0;
                const actual=Math.max(0,Number(size)||0);
                if (!id || !actual || actual > this.maxSingleBufferShadowBytes()
                    || !this.reserveBufferShadowBytes(id,actual)) return null;
                const gl=window.__gaiusWebGL;
                const object=this.buffers.get(id);
                if (!object || !gl.getBufferSubData) return null;
                const previousId=this.boundBuffers.get(gl.COPY_READ_BUFFER)|0;
                const previous=previousId ? this.buffers.get(previousId) : null;
                const bindingMatches=previousId===id;
                const startedAt=(typeof performance !== 'undefined' && performance.now)
                  ? performance.now() : Date.now();
                const copy=new Uint8Array(actual);
                try {
                  if (!bindingMatches) gl.bindBuffer(gl.COPY_READ_BUFFER,object);
                  gl.getBufferSubData(gl.COPY_READ_BUFFER,0,copy);
                } catch (error) {
                  const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.bufferShadowReadbackFailures=(stats.bufferShadowReadbackFailures||0)+1;
                  return null;
                } finally {
                  if (!bindingMatches) gl.bindBuffer(gl.COPY_READ_BUFFER,previous);
                }
                this.bufferBytes.set(id,copy);
                this.bufferShadowTotalBytes=(Number(this.bufferShadowTotalBytes)||0)+copy.byteLength;
                this.touchBufferShadow(id,copy.byteLength);
                this.updateBufferShadowTelemetry();
                this.noteBufferShadowCopy(copy.byteLength,startedAt,true);
                return copy;
              };
              window.__gaiusGL.shadowBufferData=function(buffer,data,size) {
                if (!buffer) return;
                const actual=Number(size !== undefined && size !== null ? size : (data ? data.byteLength : 0));
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                this.deleteBufferShadow(buffer);
                if (!Number.isFinite(actual) || actual < 0 || actual > 268435456) {
                  stats.bufferShadowDropped=(stats.bufferShadowDropped||0)+1;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                if (!data || !actual) {
                  stats.bufferShadowSkippedEmpty=(stats.bufferShadowSkippedEmpty||0)+1;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                if (actual > this.maxSingleBufferShadowBytes()
                    || !this.reserveBufferShadowBytes(buffer,actual)) {
                  stats.bufferShadowSkippedLarge=(stats.bufferShadowSkippedLarge||0)+1;
                  stats.bufferShadowSkippedLargeBytes=(stats.bufferShadowSkippedLargeBytes||0)+actual;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                const startedAt=(typeof performance !== 'undefined' && performance.now)
                  ? performance.now() : Date.now();
                const copy=new Uint8Array(actual);
                const source=new Uint8Array(data.buffer,data.byteOffset || 0,Math.min(data.byteLength,actual));
                copy.set(source,0);
                this.bufferBytes.set(buffer,copy);
                this.bufferShadowTotalBytes=(this.bufferShadowTotalBytes||0)+copy.byteLength;
                this.touchBufferShadow(buffer,copy.byteLength);
                this.bumpBufferVersion(buffer);
                this.updateBufferShadowTelemetry();
                this.noteBufferShadowCopy(copy.byteLength,startedAt,false);
              };
              window.__gaiusGL.shadowBufferSubData=function(buffer,offset,data) {
                if (!buffer || !data) return;
                const start=Number(offset);
                if (!Number.isFinite(start) || start < 0) return;
                const source=new Uint8Array(data.buffer,data.byteOffset || 0,data.byteLength);
                const end=start+source.byteLength;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                this.trimBufferShadows();
                if (end > 268435456 || end > this.maxSingleBufferShadowBytes()) {
                  this.deleteBufferShadow(buffer);
                  stats.bufferShadowSkippedLarge=(stats.bufferShadowSkippedLarge||0)+1;
                  stats.bufferShadowSkippedLargeBytes=(stats.bufferShadowSkippedLargeBytes||0)+end;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                let current=this.bufferBytes.get(buffer);
                const known=this.bufferSizes.get(buffer) || 0;
                var previousLength=current && current.byteLength ? current.byteLength : 0;
                const allocation=Math.max(end,known);
                if (allocation > this.maxSingleBufferShadowBytes()
                    || (allocation > previousLength
                      && !this.reserveBufferShadowBytes(buffer,allocation))) {
                  this.deleteBufferShadow(buffer);
                  stats.bufferShadowSkippedLarge=(stats.bufferShadowSkippedLarge||0)+1;
                  stats.bufferShadowSkippedLargeBytes=(stats.bufferShadowSkippedLargeBytes||0)+allocation;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                const startedAt=(typeof performance !== 'undefined' && performance.now)
                  ? performance.now() : Date.now();
                const oldCurrent=current;
                if (!current || current.byteLength < end) {
                  const next=new Uint8Array(allocation);
                  if (current) next.set(current,0);
                  current=next;
                  if (!oldCurrent && known > 0) {
                    stats.bufferShadowSubDataAllocations=
                      (stats.bufferShadowSubDataAllocations||0)+1;
                  }
                }
                current.set(source,start);
                this.bufferBytes.set(buffer,current);
                if (current.byteLength !== previousLength) {
                  this.bufferShadowTotalBytes=(Number(this.bufferShadowTotalBytes)||0)+current.byteLength;
                }
                this.touchBufferShadow(buffer,current.byteLength);
                this.bumpBufferVersion(buffer);
                this.updateBufferShadowTelemetry();
                this.noteBufferShadowCopy(
                  source.byteLength+(oldCurrent && oldCurrent!==current ? oldCurrent.byteLength : 0),
                  startedAt,false);
              };
              window.__gaiusGL.getVaoEmu=function() {
                const id=this.currentVaoId|0;
                let vao=this.vaoEmu.get(id);
                if (!vao) {
                  vao=this.newVaoEmu();
                  this.vaoEmu.set(id,vao);
                }
                return vao;
              };
              window.__gaiusGL.ensureColorWritesForFramebuffer=function(drawFramebuffer, mask) {
                if ((drawFramebuffer|0)!==0) return;
                if (mask !== undefined && ((mask|0) & 0x4000) === 0) return;
                if ((this.colorMaskBits|0)===15) return;
                window.__gaiusWebGL.colorMask(true,true,true,true);
                this.colorMask=[true,true,true,true];
                this.colorMaskBits=15;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.defaultFramebufferColorMaskRepairs=(stats.defaultFramebufferColorMaskRepairs||0)+1;
              };
              window.__gaiusGL.ensureDefaultFramebufferColorWrites=function(mask) {
                this.ensureColorWritesForFramebuffer(this.framebufferBindings.draw|0,mask);
              };
              window.__gaiusGL.applyAttribBinding=function(attrib) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const index=attrib|0;
                const binding=vao.attribBindings.has(index) ? (vao.attribBindings.get(index)|0) : index;
                const format=vao.attribFormats.get(index);
                const vertexBuffer=vao.vertexBuffers.get(binding);
                if (!format || !vertexBuffer || !vertexBuffer.buffer) {
                  this.setAttribBufferPresence(vao,index,false);
                  this.setAttribMisaligned(vao,index,false);
                  return;
                }
                const bufferObject=vertexBuffer.buffer ? this.buffers.get(vertexBuffer.buffer|0) : null;
                if (!bufferObject) {
                  this.setAttribBufferPresence(vao,index,false);
                  this.setAttribMisaligned(vao,index,false);
                  return;
                }
                const previousId=this.boundBuffers.get(gl.ARRAY_BUFFER)|0;
                const previous=previousId ? this.buffers.get(previousId) : null;
                const offset=Number(vertexBuffer.offset || 0) + Number(format.relativeOffset || 0);
                const stride=vertexBuffer.stride|0;
                const expectedInteger=this.expectedAttribInteger(index);
                const effectiveInteger=expectedInteger===null ? !!format.integer : !!expectedInteger;
                const pointer={
                  index:index,
                  size:format.size|0,
                  type:format.type|0,
                  normalized:effectiveInteger ? false : !!format.normalized,
                  stride:stride,
                  offset:offset,
                  integer:effectiveInteger,
                  buffer:vertexBuffer.buffer|0
                };
                const componentBytes=this.componentBytes(format.type);
                const aligned=(offset % componentBytes)===0 && (stride===0 || (stride % componentBytes)===0);
                const previousPointer=vao.attribPointers.get(index);
                const previousMisaligned=vao.misalignedAttribs && vao.misalignedAttribs.has(index);
                const previousPresence=vao.attribHasBuffer.has(index);
                const typeLayoutChanged=!previousPointer
                  || !!previousPointer.integer!==!!pointer.integer
                  || (previousPointer.size|0)!==(pointer.size|0)
                  || (previousPointer.type|0)!==(pointer.type|0);
                const samePointer=this.sameAttribPointer(previousPointer,pointer);
                if (previousPresence && samePointer && previousMisaligned===!aligned) {
                  if (this.hotPathTelemetryEnabled) {
                    const skips=((this.attribPointerFastSkips||0)+1)|0;
                    this.attribPointerFastSkips=skips;
                    if ((skips & 255)===0) {
                      var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                      stats.attribPointerFastSkips=skips;
                    }
                  }
                  return;
                }
                this.recordAttribPointerAdapt(index,!!format.integer,effectiveInteger,format.type|0);
                vao.attribPointers.set(index,pointer);
                this.setAttribBufferPresence(vao,index,true);
                this.setAttribMisaligned(vao,index,!aligned,vertexBuffer.buffer|0);
                if (typeLayoutChanged) this.bumpVaoAttribTypeVersion(vao);
                if (previousPresence && (!samePointer || previousMisaligned!==!aligned)) {
                  this.bumpVaoAttribVersion(vao);
                }
                if (aligned) {
                  gl.bindBuffer(gl.ARRAY_BUFFER,bufferObject);
                  if (effectiveInteger) {
                    gl.vertexAttribIPointer(index,format.size|0,format.type|0,stride,offset);
                  } else {
                    gl.vertexAttribPointer(index,format.size|0,format.type|0,!!pointer.normalized,stride,offset);
                  }
                } else {
                  this.markBufferShadowRequired(vertexBuffer.buffer|0,'misaligned-attrib-binding');
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
                }
                gl.bindBuffer(gl.ARRAY_BUFFER,previous);
              };
              window.__gaiusGL.applyVertexBinding=function(binding) {
                const vao=this.getVaoEmu();
                const target=binding|0;
                vao.attribFormats.forEach(function(format, attrib) {
                  const attribBinding=vao.attribBindings.has(attrib) ? (vao.attribBindings.get(attrib)|0) : (attrib|0);
                  if (attribBinding === target) {
                    this.applyAttribBinding(attrib|0);
                  }
                }, this);
              };
              window.__gaiusGL.activeAttribLocations=function() {
                const program=this.currentProgram|0;
                if (!program) return null;
                let attribs=this.programAttribs.get(program);
                if (!attribs) {
                  this.refreshProgramAttribs(program);
                  attribs=this.programAttribs.get(program);
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.activeAttribLazyRefresh=(stats.activeAttribLazyRefresh||0)+1;
                }
                if (!attribs) return null;
                if (!attribs.byLocation) {
                  attribs.byLocation=new Map();
                  for (var i=0;i<attribs.length;i++) {
                    const entry=attribs[i];
                    const location=entry && (entry.location|0);
                    if (location >= 0) attribs.byLocation.set(location|0,entry);
                  }
                }
                return attribs.byLocation;
              };
              window.__gaiusGL.attribIsActive=function(active, index) {
                return !active || active.has(index|0);
              };
              window.__gaiusGL.restoreDirectAttribPointers=function(active) {
                const gl=window.__gaiusWebGL;
                const state=this;
                const vao=this.getVaoEmu();
                const version=vao.attribVersion||0;
                const program=this.currentProgram|0;
                const globalVersion=this.programVersion||0;
                if (!vao.directAttribDirty) {
                  return 0;
                }
                if ((vao.directAttribVersion|0)===version
                    && (vao.directAttribProgram|0)===program
                    && (vao.directAttribGlobalVersion|0)===globalVersion) {
                  return 0;
                }
                const previousArrayId=this.boundBuffers.get(gl.ARRAY_BUFFER)|0;
                const previousArray=previousArrayId ? this.buffers.get(previousArrayId) : null;
                let restored=0;
                const restoreOne=function(attrib) {
                  const index=attrib|0;
                  if (!vao.enabledAttribs.has(index) || !state.attribIsActive(active,index)) return;
                  if (vao.misalignedAttribs && vao.misalignedAttribs.has(index)) return;
                  const pointer=vao.attribPointers.get(index);
                  if (!pointer || !pointer.buffer || !vao.attribHasBuffer.has(index)) return;
                  if (state.bindAttribPointerAtOffset(pointer,Number(pointer.offset),true)) {
                    restored++;
                  }
                };
                if (active) {
                  active.forEach(function(attrib) { restoreOne(attrib); });
                } else {
                  vao.enabledAttribs.forEach(function(attrib) { restoreOne(attrib); });
                }
                gl.bindBuffer(gl.ARRAY_BUFFER,previousArray);
                vao.directAttribVersion=version;
                vao.directAttribProgram=program;
                vao.directAttribGlobalVersion=globalVersion;
                vao.directAttribDirty=false;
                if (restored) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.directAttribRestores=(stats.directAttribRestores||0)+1;
                  stats.directAttribRestorePointers=(stats.directAttribRestorePointers||0)+restored;
                }
                return restored;
              };
            }
            """)
    private static native void initializeJs();

    @JSBody(script = """
            const gl=window.__gaiusWebGL;
            if (!gl) throw new Error('WebGL2 context is not initialized');
            const state=window.__gaiusGL;
            if (state && !state.__drawCompatibilityInit) {
              state.__drawCompatibilityInit=true;
              window.__gaiusGL.ensureAlignedAttribs=function() {
                var gl=window.__gaiusWebGL;
                var vao=this.getVaoEmu();
                var version=vao.attribVersion||0;
                var program=this.currentProgram|0;
                var globalVersion=this.programVersion||0;
                var state=this;
                if (!vao.misalignedAttribs || !vao.misalignedAttribs.size) {
                  if (vao.directAttribDirty) this.restoreDirectAttribPointers(null);
                  if ((vao.alignedAttribVersion|0)!==version
                      || (vao.alignedAttribProgram|0)!==program
                      || (vao.alignedAttribGlobalVersion|0)!==globalVersion) {
                    vao.alignedAttribVersion=version;
                    vao.alignedAttribProgram=program;
                    vao.alignedAttribGlobalVersion=globalVersion;
                  }
                  return 0;
                }
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                var activeMisaligned=false;
                var active=this.activeAttribLocations();
                if (vao.misalignedAttribs && vao.misalignedAttribs.size) {
                  vao.misalignedAttribs.forEach(function(attrib) {
                    if (state.attribIsActive(active,attrib|0) && vao.enabledAttribs.has(attrib|0)) {
                      activeMisaligned=true;
                    }
                  });
                }
                if (!activeMisaligned) {
                  this.restoreDirectAttribPointers(active);
                  vao.alignedAttribVersion=version;
                  vao.alignedAttribProgram=program;
                  vao.alignedAttribGlobalVersion=globalVersion;
                  return 0;
                }
                if ((vao.alignedAttribVersion|0)===version
                    && (vao.alignedAttribProgram|0)===program
                    && (vao.alignedAttribGlobalVersion|0)===globalVersion) {
                  stats.alignedAttribFastSkips=(stats.alignedAttribFastSkips||0)+1;
                  return 0;
                }
                vao.alignedAttribInvalidated=false;
                var groups=new Map();
                vao.misalignedAttribs.forEach(function(attrib) {
                  var index=attrib|0;
                  if (!state.attribIsActive(active,index)) return;
                  if (!vao.enabledAttribs.has(index)) return;
                  var pointer=vao.attribPointers.get(index);
                  if (!pointer || !pointer.buffer || !vao.attribHasBuffer.has(index)) return;
                  var group=groups.get(pointer.buffer|0);
                  if (!group) {
                    group=[];
                    groups.set(pointer.buffer|0,group);
                  }
                  group.push(pointer);
                });
                if (!groups.size) return 0;
                var aligned=0;
                var complete=true;
                var previousArrayId=this.boundBuffers.get(gl.ARRAY_BUFFER)|0;
                var previousArray=previousArrayId ? this.buffers.get(previousArrayId) : null;
                groups.forEach(function(_misalignedPointers, sourceBuffer) {
                  var pointers=[];
                  vao.enabledAttribs.forEach(function(attrib) {
                    if (!state.attribIsActive(active,attrib|0)) return;
                    var pointer=vao.attribPointers.get(attrib|0);
                    if (pointer && (pointer.buffer|0)===(sourceBuffer|0) && vao.attribHasBuffer.has(attrib|0)) {
                      pointers.push(pointer);
                    }
                  });
                  pointers.sort(function(a,b){ return (a.index|0)-(b.index|0); });
                  var source=this.bufferBytes.get(sourceBuffer|0);
                  if (!source) {
                    source=this.readBufferShadow(
                      sourceBuffer|0,this.bufferSizes.get(sourceBuffer|0)||0);
                  }
                  if (!source || !source.byteLength || !pointers.length) {
                    stats.alignedAttribMissingSource=(stats.alignedAttribMissingSource||0)+1;
                    for (var msi=0;msi<pointers.length;msi++) vao.missingEnabledAttribs.add(pointers[msi].index|0);
                    complete=false;
                    return;
                  }
                  var vertexCount=Number.POSITIVE_INFINITY;
                  var layoutKey='';
                  for (var pointerIndex=0; pointerIndex<pointers.length; pointerIndex++) {
                    var pointer=pointers[pointerIndex];
                    var componentBytes=this.componentBytes(pointer.type);
                    var sourceStride=pointer.stride ? (pointer.stride|0) : ((pointer.size|0)*componentBytes);
                    var bytes=(pointer.size|0)*componentBytes;
                    var available=source.byteLength-Number(pointer.offset)-bytes;
                    var count=available>=0 ? Math.floor(available/sourceStride)+1 : 0;
                    vertexCount=Math.min(vertexCount,count);
                    layoutKey += pointer.index+','+pointer.size+','+pointer.type+','+(pointer.normalized?1:0)+','
                      +pointer.stride+','+pointer.offset+','+(pointer.integer?1:0)+';';
                  }
                  if (!Number.isFinite(vertexCount) || vertexCount <= 0) {
                    stats.alignedAttribNoVertices=(stats.alignedAttribNoVertices||0)+1;
                    for (var nvi=0;nvi<pointers.length;nvi++) vao.missingEnabledAttribs.add(pointers[nvi].index|0);
                    complete=false;
                    return;
                  }
                  var bufferVersion=this.bufferVersions.get(sourceBuffer|0)||0;
                  var key=(sourceBuffer|0)+':'+bufferVersion+':'+vertexCount+':'+layoutKey;
                  var entry=this.alignedAttribCache.get(key);
                  if (entry) this.touchAlignedAttribEntry(entry);
                  if (!entry) {
                    var cursor=0;
                    var layouts=[];
                    for (var layoutPointerIndex=0; layoutPointerIndex<pointers.length; layoutPointerIndex++) {
                      var layoutPointer=pointers[layoutPointerIndex];
                      var layoutComponentBytes=this.componentBytes(layoutPointer.type);
                      cursor=this.align(cursor,layoutComponentBytes);
                      var layoutOffset=cursor;
                      var layoutBytes=(layoutPointer.size|0)*layoutComponentBytes;
                      layouts.push({
                        pointer:layoutPointer,
                        offset:layoutOffset,
                        bytes:layoutBytes,
                        componentBytes:layoutComponentBytes
                      });
                      cursor=layoutOffset+layoutBytes;
                    }
                    var alignedStride=this.align(cursor,4);
                    var repacked=null;
                    var wasmHotpath=window.__gaiusWasmHotpath;
                    if (wasmHotpath && wasmHotpath.ready && wasmHotpath.repackInterleaved) {
                      var wasmLayouts=[];
                      for (var wasmLayoutIndex=0; wasmLayoutIndex<layouts.length; wasmLayoutIndex++) {
                        var wasmLayout=layouts[wasmLayoutIndex];
                        var wasmPointer=wasmLayout.pointer;
                        var wasmSourceStride=wasmPointer.stride
                          ? (wasmPointer.stride|0)
                          : ((wasmPointer.size|0)*wasmLayout.componentBytes);
                        wasmLayouts.push({
                          sourceOffset:Number(wasmPointer.offset)|0,
                          sourceStride:wasmSourceStride|0,
                          bytes:wasmLayout.bytes|0,
                          targetOffset:wasmLayout.offset|0
                        });
                      }
                      repacked=wasmHotpath.repackInterleaved(source, vertexCount|0, alignedStride|0, wasmLayouts);
                      if (repacked) {
                        stats.alignedAttribWasm=(stats.alignedAttribWasm||0)+1;
                        stats.alignedAttribWasmBytes=(stats.alignedAttribWasmBytes||0)+repacked.byteLength;
                      } else {
                        stats.alignedAttribWasmFallback=(stats.alignedAttribWasmFallback||0)+1;
                      }
                    } else if (wasmHotpath && wasmHotpath.error) {
                      stats.alignedAttribWasmUnavailable=(stats.alignedAttribWasmUnavailable||0)+1;
                    }
                    if (!repacked) {
                      repacked=new Uint8Array(vertexCount*alignedStride);
                      for (var vertex=0; vertex<vertexCount; vertex++) {
                        for (var layoutIndex=0; layoutIndex<layouts.length; layoutIndex++) {
                          var layout=layouts[layoutIndex];
                          var copyPointer=layout.pointer;
                          var copyComponentBytes=layout.componentBytes;
                          var copySourceStride=copyPointer.stride
                            ? (copyPointer.stride|0)
                            : ((copyPointer.size|0)*copyComponentBytes);
                          var sourceOffset=vertex*copySourceStride+Number(copyPointer.offset);
                          var targetOffset=vertex*alignedStride+layout.offset;
                          repacked.set(source.subarray(sourceOffset,sourceOffset+layout.bytes),targetOffset);
                        }
                      }
                      stats.alignedAttribJsFallback=(stats.alignedAttribJsFallback||0)+1;
                    }
                    var repackedBytes=Math.max(0,Number(repacked.byteLength)||0);
                    if (!this.trimAlignedAttribCache(repackedBytes)) {
                      stats.alignedAttribBudgetFallbacks=(stats.alignedAttribBudgetFallbacks||0)+1;
                      for (var abi=0;abi<pointers.length;abi++) {
                        vao.missingEnabledAttribs.add(pointers[abi].index|0);
                      }
                      complete=false;
                      return;
                    }
                    var buffer=null;
                    try {
                      buffer=gl.createBuffer();
                      if (!buffer) throw new Error('createBuffer returned null');
                      gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
                      gl.bufferData(gl.ARRAY_BUFFER,repacked,gl.STATIC_DRAW);
                    } catch (error) {
                      if (buffer) {
                        try { gl.deleteBuffer(buffer); } catch (ignored) {}
                      }
                      stats.alignedAttribUploadFailures=(stats.alignedAttribUploadFailures||0)+1;
                      stats.alignedAttribUploadFailureReason=String(
                        error && (error.message||error.name)||error);
                      for (var afi=0;afi<pointers.length;afi++) {
                        vao.missingEnabledAttribs.add(pointers[afi].index|0);
                      }
                      complete=false;
                      return;
                    }
                    var entryLayouts=[];
                    for (var entryLayoutIndex=0; entryLayoutIndex<layouts.length; entryLayoutIndex++) {
                      var entryLayout=layouts[entryLayoutIndex];
                      entryLayouts.push({
                        index:entryLayout.pointer.index|0,
                        size:entryLayout.pointer.size|0,
                        type:entryLayout.pointer.type|0,
                        normalized:!!entryLayout.pointer.normalized,
                        integer:!!entryLayout.pointer.integer,
                        offset:entryLayout.offset|0
                      });
                    }
                    entry={buffer:buffer,stride:alignedStride,layouts:entryLayouts,
                      bytes:repackedBytes,source:sourceBuffer|0,cacheKey:key,
                      deleted:false,vaoRefs:new Set()};
                    this.alignedAttribCache.set(key,entry);
                    this.registerBufferCacheKey(this.alignedAttribCacheKeys,sourceBuffer|0,key);
                    this.alignedAttribCacheTotalBytes=
                      (Number(this.alignedAttribCacheTotalBytes)||0)+repackedBytes;
                    this.updateAlignedAttribTelemetry();
                    stats.alignedAttribBuffers=(stats.alignedAttribBuffers||0)+1;
                    stats.alignedAttribBytes=(stats.alignedAttribBytes||0)+repackedBytes;
                  }
                  this.trackAlignedAttribEntryVao(entry,vao);
                  gl.bindBuffer(gl.ARRAY_BUFFER,entry.buffer);
                  for (var bindLayoutIndex=0; bindLayoutIndex<entry.layouts.length; bindLayoutIndex++) {
                    var bindLayout=entry.layouts[bindLayoutIndex];
                    if (bindLayout.integer) {
                      gl.vertexAttribIPointer(
                        bindLayout.index,bindLayout.size,bindLayout.type,entry.stride,bindLayout.offset);
                    } else {
                      gl.vertexAttribPointer(
                        bindLayout.index,bindLayout.size,bindLayout.type,
                        bindLayout.normalized,entry.stride,bindLayout.offset);
                    }
                    vao.missingEnabledAttribs.delete(bindLayout.index|0);
                  }
                  aligned += pointers.length;
                }, this);
                gl.bindBuffer(gl.ARRAY_BUFFER,previousArray);
                if (aligned) {
                  vao.directAttribVersion=-1;
                  vao.directAttribProgram=-1;
                  vao.directAttribGlobalVersion=-1;
                  vao.directAttribDirty=true;
                  stats.alignedAttribDraws=(stats.alignedAttribDraws||0)+1;
                  stats.alignedAttribPointers=(stats.alignedAttribPointers||0)+aligned;
                }
                if (complete && !vao.alignedAttribInvalidated) {
                  vao.alignedAttribVersion=version;
                  vao.alignedAttribProgram=program;
                  vao.alignedAttribGlobalVersion=globalVersion;
                }
                return aligned;
              };
              window.__gaiusGL.getBaseVertexExtension=function() {
                if (this.baseVertexExtensionChecked) {
                  return this.baseVertexExtension || null;
                }
                this.baseVertexExtensionChecked=true;
                try {
                  this.baseVertexExtension=window.__gaiusWebGL.getExtension(
                    'WEBGL_draw_instanced_base_vertex_base_instance');
                } catch (ignored) {
                  this.baseVertexExtension=null;
                }
                return this.baseVertexExtension || null;
              };
              window.__gaiusGL.hasUsableBaseVertexExtension=function() {
                const extension=this.baseVertexExtensionChecked
                  ? this.baseVertexExtension : this.getBaseVertexExtension();
                return !!(extension && (
                  extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL
                  || extension.drawElementsInstancedBaseVertexWEBGL));
              };
              window.__gaiusGL.bindAttribPointerAtOffset=function(pointer, offset, preserveDirectCache) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const bufferObject=this.buffers.get(pointer.buffer|0);
                if (!bufferObject) {
                  return false;
                }
                if (!preserveDirectCache) {
                  vao.directAttribVersion=-1;
                  vao.directAttribProgram=-1;
                  vao.directAttribGlobalVersion=-1;
                }
                gl.bindBuffer(gl.ARRAY_BUFFER,bufferObject);
                if (pointer.integer) {
                  gl.vertexAttribIPointer(
                    pointer.index|0,pointer.size|0,pointer.type|0,pointer.stride|0,Number(offset));
                } else {
                  gl.vertexAttribPointer(
                    pointer.index|0,pointer.size|0,pointer.type|0,!!pointer.normalized,pointer.stride|0,Number(offset));
                }
                return true;
              };
              window.__gaiusGL.refreshProgramAttribs=function(program) {
                const gl=window.__gaiusWebGL;
                const object=this.programs.get(program|0);
                const result=[];
                result.byLocation=new Map();
                if (object) {
                  let count=0;
                  try {
                    count=gl.getProgramParameter(object,gl.ACTIVE_ATTRIBUTES)|0;
                  } catch (ignored) {
                    count=0;
                  }
                  for (let i=0;i<count;i++) {
                    let info=null;
                    try {
                      info=gl.getActiveAttrib(object,i);
                    } catch (ignored) {
                      info=null;
                    }
                    if (!info || !info.name) continue;
                    const location=gl.getAttribLocation(object,info.name);
                    if (location < 0) continue;
                    const active={
                      location:location|0,
                      name:String(info.name),
                      type:info.type|0,
                      integer:this.isIntegerAttribType(info.type)
                    };
                    result.push(active);
                    result.byLocation.set(location|0,active);
                  }
                }
                this.programAttribs.set(program|0,result);
              };
              window.__gaiusGL.ensureProgramAttribTypes=function() {
                const gl=window.__gaiusWebGL;
                const program=this.currentProgram|0;
                let attribs=this.programAttribs.get(program);
                if (program && !attribs) {
                  this.refreshProgramAttribs(program);
                  attribs=this.programAttribs.get(program);
                  var refreshStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  refreshStats.programAttribLazyRefresh=(refreshStats.programAttribLazyRefresh||0)+1;
                }
                if (!program || !attribs || !attribs.length) return 0;
                const vao=this.getVaoEmu();
                const typeVersion=vao.attribTypeVersion||0;
                const globalVersion=this.programVersion||0;
                const cached=vao.programAttribCache ? vao.programAttribCache.get(program|0) : null;
                if (cached
                    && (cached.typeVersion|0)===typeVersion
                    && (cached.globalVersion|0)===globalVersion) {
                  return 0;
                }
                let repaired=0;
                let previousArray=null;
                for (let i=0;i<attribs.length;i++) {
                  const active=attribs[i];
                  const location=active.location|0;
                  if (!vao.enabledAttribs.has(location)) continue;
                  const pointer=vao.attribPointers.get(location);
                  if (!pointer || !pointer.buffer || !vao.attribHasBuffer.has(location)) continue;
                  const expected=!!active.integer;
                  if (!!pointer.integer === expected) continue;
                  if (previousArray===null) {
                    const previousArrayId=this.boundBuffers.get(gl.ARRAY_BUFFER)|0;
                    previousArray=previousArrayId ? this.buffers.get(previousArrayId) : false;
                  }
                  const oldInteger=!!pointer.integer;
                  pointer.integer=expected;
                  if (this.bindAttribPointerAtOffset(pointer,Number(pointer.offset),false)) {
                    repaired++;
                    var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.attribTypeRepairs=(stats.attribTypeRepairs||0)+1;
                    stats.attribTypeLast={
                      program:program,
                      location:location,
                      name:active.name,
                      shaderType:active.type|0,
                      expectedInteger:expected,
                      previousInteger:oldInteger,
                      pointerType:pointer.type|0
                    };
                  } else {
                    pointer.integer=oldInteger;
                  }
                }
                if (previousArray!==null) {
                  gl.bindBuffer(gl.ARRAY_BUFFER,previousArray || null);
                }
                if (vao.programAttribCache) {
                  if (repaired && vao.programAttribCache.size) vao.programAttribCache.clear();
                  if (vao.programAttribCache.size > 64) vao.programAttribCache.clear();
                  vao.programAttribCache.set(program|0,{typeVersion:typeVersion|0,globalVersion:globalVersion|0});
                }
                return repaired;
              };
              window.__gaiusGL.prepareDrawAttribs=function(vao) {
                const repaired=this.ensureProgramAttribTypes()|0;
                const aligned=this.ensureAlignedAttribs()|0;
                const finalMissing=vao.missingEnabledAttribs ? (vao.missingEnabledAttribs.size|0) : 0;
                if (finalMissing===0) {
                  vao.drawReadyGeneration=this.drawProgramGeneration|0;
                } else {
                  vao.drawReadyGeneration=-1;
                }
                return (repaired+aligned)|0;
              };
              window.__gaiusGL.withBaseVertexAttribs=function(baseVertex, draw) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const shiftedAttribPointers=[];
                const previousArrayId=this.boundBuffers.get(gl.ARRAY_BUFFER)|0;
                const previousArray=previousArrayId ? this.buffers.get(previousArrayId) : null;
                const stats=this.hotPathTelemetryEnabled
                  ? (window.__gaiusGLStats || (window.__gaiusGLStats={})) : null;
                try {
                  vao.enabledAttribs.forEach(function(attrib) {
                    const pointer=vao.attribPointers.get(attrib|0);
                    if (!pointer || !pointer.buffer || !vao.attribHasBuffer.has(attrib|0)) {
                      return;
                    }
                    const componentBytes=this.componentBytes(pointer.type);
                    const stride=(pointer.stride|0) || ((pointer.size|0)*componentBytes);
                    const shiftedOffset=Number(pointer.offset) + Number(baseVertex)*stride;
                    if (!Number.isFinite(shiftedOffset) || shiftedOffset < 0) {
                      if (stats) stats.baseVertexBadOffset=(stats.baseVertexBadOffset||0)+1;
                      return;
                    }
                    if (this.bindAttribPointerAtOffset(pointer,shiftedOffset,false)) {
                      shiftedAttribPointers.push(pointer);
                      vao.directAttribDirty=true;
                    } else {
                      if (stats) stats.baseVertexMissingBuffer=(stats.baseVertexMissingBuffer||0)+1;
                    }
                  }, this);
                  if (stats && shiftedAttribPointers.length) {
                    stats.baseVertexFallbackDraws=(stats.baseVertexFallbackDraws||0)+1;
                    stats.baseVertexShiftedAttribs=(stats.baseVertexShiftedAttribs||0)+shiftedAttribPointers.length;
                    stats.baseVertexLast=Number(baseVertex)|0;
                  }
                  draw();
                } finally {
                  for (var i=0;i<shiftedAttribPointers.length;i++) {
                    this.bindAttribPointerAtOffset(
                      shiftedAttribPointers[i],
                      Number(shiftedAttribPointers[i].offset),
                      true);
                  }
                  if (shiftedAttribPointers.length) {
                    vao.directAttribVersion=vao.attribVersion||0;
                    vao.directAttribProgram=this.currentProgram|0;
                    vao.directAttribGlobalVersion=this.programVersion||0;
                    vao.directAttribDirty=false;
                    if (stats) {
                      stats.baseVertexDirectRestores=(stats.baseVertexDirectRestores||0)+1;
                      stats.baseVertexDirectRestorePointers=(stats.baseVertexDirectRestorePointers||0)+shiftedAttribPointers.length;
                    }
                  }
                  gl.bindBuffer(gl.ARRAY_BUFFER,previousArray);
                }
              };
              window.__gaiusGL.indexBytes=function(type) {
                switch (type|0) {
                  case 0x1401: return 1;
                  case 0x1403: return 2;
                  case 0x1405: return 4;
                  default: return 0;
                }
              };
              window.__gaiusGL.indexRestartValue=function(type) {
                switch (type|0) {
                  case 0x1401: return 255;
                  case 0x1403: return 65535;
                  case 0x1405: return 4294967295;
                  default: return -1;
                }
              };
              window.__gaiusGL.touchShiftedIndexEntry=function(entry) {
                if (!entry || entry.deleted || !entry.cacheKey) return;
                if (this.shiftedIndexCache.get(entry.cacheKey)!==entry) return;
                this.shiftedIndexCache.delete(entry.cacheKey);
                this.shiftedIndexCache.set(entry.cacheKey,entry);
              };
              window.__gaiusGL.trimShiftedIndexCache=function(incomingBytes) {
                const incoming=Math.max(0,Number(incomingBytes)||0);
                const limit=this.maxShiftedIndexCacheBytes();
                if (!Number.isFinite(limit) || incoming > limit) return false;
                while ((Number(this.shiftedIndexCacheTotalBytes)||0)+incoming > limit
                    && this.shiftedIndexCache.size) {
                  const oldestKey=this.shiftedIndexCache.keys().next().value;
                  this.deleteShiftedIndexEntry(oldestKey,true);
                }
                this.updateShiftedIndexTelemetry();
                return (Number(this.shiftedIndexCacheTotalBytes)||0)+incoming <= limit;
              };
              window.__gaiusGL.noteShiftedIndexCreated=function() {
                const created=((this.shiftedIndexCreatedThisFrame|0)+1)|0;
                this.shiftedIndexCreatedThisFrame=created;
                this.shiftedIndexCreatedFrameHighWater=Math.max(
                  this.shiftedIndexCreatedFrameHighWater|0,created);
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.baseVertexIndexCreatedThisFrame=created;
                stats.baseVertexIndexCreatedFrameHighWater=this.shiftedIndexCreatedFrameHighWater|0;
                if (this.shiftedIndexFrameScheduled
                    || typeof requestAnimationFrame !== 'function') return;
                this.shiftedIndexFrameScheduled=true;
                requestAnimationFrame(() => {
                  const frameCreated=this.shiftedIndexCreatedThisFrame|0;
                  const frameStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  frameStats.baseVertexIndexCreatedLastFrame=frameCreated;
                  this.shiftedIndexCreatedFrameHighWater=Math.max(
                    this.shiftedIndexCreatedFrameHighWater|0,frameCreated);
                  frameStats.baseVertexIndexCreatedFrameHighWater=
                    this.shiftedIndexCreatedFrameHighWater|0;
                  this.shiftedIndexCreatedThisFrame=0;
                  frameStats.baseVertexIndexCreatedThisFrame=0;
                  this.shiftedIndexFrameScheduled=false;
                });
              };
              window.__gaiusGL.cacheShiftedIndexBuffer=function(vao,type,offset,count,baseVertex) {
                const gl=window.__gaiusWebGL;
                const elementBuffer=vao.elementArrayBuffer|0;
                const start=Number(offset);
                const length=count|0;
                const base=baseVertex|0;
                const telemetry=!!this.hotPathTelemetryEnabled;
                let stats=null;
                this.trimShiftedIndexCache(0);
                const cached=vao.shiftedIndexLast;
                if (cached && !cached.deleted
                    && (cached.element|0)===elementBuffer
                    && (cached.inputType|0)===(type|0) && cached.offset===start
                    && (cached.inputCount|0)===length && (cached.base|0)===base) {
                  if (telemetry) {
                    stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.baseVertexIndexCacheHits=(stats.baseVertexIndexCacheHits||0)+1;
                    stats.baseVertexIndexLastCacheHits=(stats.baseVertexIndexLastCacheHits||0)+1;
                  }
                  this.trackShiftedIndexEntryVao(cached,vao);
                  this.touchShiftedIndexEntry(cached);
                  return cached;
                }
                let fastKey=elementBuffer|0;
                fastKey=Math.imul((fastKey^(type|0))|0,16777619);
                fastKey=Math.imul((fastKey^(start|0))|0,16777619);
                fastKey=Math.imul((fastKey^(Math.floor(start/4294967296)|0))|0,16777619);
                fastKey=Math.imul((fastKey^length)|0,16777619);
                fastKey=Math.imul((fastKey^base)|0,16777619);
                const fastCache=vao.shiftedIndexFastCache
                  || (vao.shiftedIndexFastCache=new Map());
                const fastEntry=fastCache.get(fastKey);
                if (fastEntry && !fastEntry.deleted
                    && (fastEntry.element|0)===elementBuffer
                    && (fastEntry.inputType|0)===(type|0) && fastEntry.offset===start
                    && (fastEntry.inputCount|0)===length && (fastEntry.base|0)===base) {
                  vao.shiftedIndexLast=fastEntry;
                  if (telemetry) {
                    stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.baseVertexIndexCacheHits=(stats.baseVertexIndexCacheHits||0)+1;
                    stats.baseVertexIndexFastCacheHits=(stats.baseVertexIndexFastCacheHits||0)+1;
                  }
                  this.trackShiftedIndexEntryVao(fastEntry,vao);
                  this.touchShiftedIndexEntry(fastEntry);
                  return fastEntry;
                }
                if (fastEntry) fastCache.delete(fastKey);
                const indexBytes=this.indexBytes(type);
                stats=telemetry
                  ? (window.__gaiusGLStats || (window.__gaiusGLStats={})) : null;
                if (!elementBuffer || !indexBytes || length <= 0
                    || !Number.isFinite(start) || start < 0 || (start % indexBytes) !== 0) {
                  if (stats) {
                    stats.baseVertexIndexCacheMiss=(stats.baseVertexIndexCacheMiss||0)+1;
                    stats.baseVertexIndexCacheMisses=(stats.baseVertexIndexCacheMisses||0)+1;
                  }
                  return null;
                }
                const version=this.bufferVersions.get(elementBuffer|0)||0;
                const key=(elementBuffer|0)+':'+version+':'+(type|0)+':'+start+':'+length+':'+base;
                let entry=this.shiftedIndexCache.get(key);
                if (entry && !entry.deleted) {
                  vao.shiftedIndexLast=entry;
                  if (!fastCache.has(fastKey) && fastCache.size >= 64) fastCache.clear();
                  fastCache.set(fastKey,entry);
                  this.trackShiftedIndexEntryVao(entry,vao);
                  if (stats) {
                    stats.baseVertexIndexCacheHits=(stats.baseVertexIndexCacheHits||0)+1;
                    stats.baseVertexIndexMapCacheHits=(stats.baseVertexIndexMapCacheHits||0)+1;
                  }
                  this.touchShiftedIndexEntry(entry);
                  return entry;
                }
                if (stats) {
                  stats.baseVertexIndexCacheMiss=(stats.baseVertexIndexCacheMiss||0)+1;
                  stats.baseVertexIndexCacheMisses=(stats.baseVertexIndexCacheMisses||0)+1;
                }
                let source=this.bufferBytes.get(elementBuffer);
                if (!source) {
                  if (stats) {
                    stats.baseVertexIndexMissingUploadShadow=
                      (stats.baseVertexIndexMissingUploadShadow||0)+1;
                  }
                  return null;
                }
                if (!source || start + length * indexBytes > source.byteLength) return null;
                this.touchBufferShadow(elementBuffer,source.byteLength);
                let maxIndex=0;
                let minIndex=4294967295;
                let outputType=type|0;
                let output;
                const byteOffset=source.byteOffset + start;
                let values;
                if ((type|0) === 0x1401) {
                  values=new Uint8Array(source.buffer,byteOffset,length);
                } else if ((type|0) === 0x1403) {
                  values=new Uint16Array(source.buffer,byteOffset,length);
                } else {
                  values=new Uint32Array(source.buffer,byteOffset,length);
                }
                const inputRestart=this.indexRestartValue(type);
                let hasRestart=false;
                for (let i=0;i<length;i++) {
                  if (Number(values[i])===inputRestart) {
                    hasRestart=true;
                    break;
                  }
                }
                const deriveStartedAt=(typeof performance !== 'undefined' && performance.now)
                  ? performance.now() : Date.now();
                const wasmHotpath=window.__gaiusWasmHotpath;
                if (!hasRestart && wasmHotpath && wasmHotpath.ready && wasmHotpath.shiftIndices) {
                  const wasmShifted=wasmHotpath.shiftIndices(type|0, source, start, length, base);
                  if (wasmShifted && wasmShifted.output) {
                    outputType=wasmShifted.type|0;
                    output=wasmShifted.output;
                    minIndex=wasmShifted.min>>>0;
                    maxIndex=wasmShifted.max>>>0;
                    if (stats) {
                      stats.baseVertexIndexWasm=(stats.baseVertexIndexWasm||0)+1;
                      stats.baseVertexIndexWasmBytes=(stats.baseVertexIndexWasmBytes||0)+(wasmShifted.bytes|0);
                    }
                    if (maxIndex===this.indexRestartValue(outputType)) {
                      output=null;
                      if (stats) {
                        stats.baseVertexIndexWasmRestartFallbacks=
                          (stats.baseVertexIndexWasmRestartFallbacks||0)+1;
                      }
                    }
                  } else {
                    if (stats) stats.baseVertexIndexWasmFallback=(stats.baseVertexIndexWasmFallback||0)+1;
                  }
                } else if (wasmHotpath && wasmHotpath.error) {
                  if (stats) stats.baseVertexIndexWasmUnavailable=(stats.baseVertexIndexWasmUnavailable||0)+1;
                }
                if (!output) {
                  let hasNonRestart=false;
                  minIndex=4294967295;
                  maxIndex=0;
                  for (let i=0;i<length;i++) {
                    const inputValue=Number(values[i]);
                    if (inputValue===inputRestart) continue;
                    const shiftedIndexValue=inputValue + base;
                    if (shiftedIndexValue < 0 || shiftedIndexValue > 4294967295) {
                      if (stats) stats.baseVertexIndexOutOfRange=(stats.baseVertexIndexOutOfRange||0)+1;
                      return null;
                    }
                    hasNonRestart=true;
                    if (shiftedIndexValue > maxIndex) maxIndex=shiftedIndexValue;
                    if (shiftedIndexValue < minIndex) minIndex=shiftedIndexValue;
                  }
                  if (!hasNonRestart) minIndex=0;
                  if (maxIndex < 255 && (type|0) === 0x1401) {
                    output=new Uint8Array(length);
                  } else if (maxIndex < 65535 && (type|0) !== 0x1405) {
                    outputType=0x1403;
                    output=new Uint16Array(length);
                  } else if (maxIndex < 4294967295) {
                    outputType=0x1405;
                    output=new Uint32Array(length);
                  } else {
                    if (stats) stats.baseVertexIndexRestartCollision=
                      (stats.baseVertexIndexRestartCollision||0)+1;
                    return null;
                  }
                  const outputRestart=this.indexRestartValue(outputType);
                  for (let i=0;i<length;i++) {
                    const shiftedInputValue=Number(values[i]);
                    output[i]=shiftedInputValue===inputRestart
                      ? outputRestart : shiftedInputValue + base;
                  }
                  if (stats) stats.baseVertexIndexJsFallback=(stats.baseVertexIndexJsFallback||0)+1;
                }
                if (stats) {
                  const deriveEndedAt=(typeof performance !== 'undefined' && performance.now)
                    ? performance.now() : Date.now();
                  stats.baseVertexIndexCopyBytes=
                    (stats.baseVertexIndexCopyBytes||0)+output.byteLength;
                  stats.baseVertexIndexCopyMs=
                    (stats.baseVertexIndexCopyMs||0)+Math.max(0,deriveEndedAt-deriveStartedAt);
                }
                if (!this.trimShiftedIndexCache(output.byteLength)) {
                  if (stats) stats.baseVertexIndexBudgetFallbacks=
                    (stats.baseVertexIndexBudgetFallbacks||0)+1;
                  return null;
                }
                let buffer=null;
                const previousPhysicalElement=vao.actualElementArrayBuffer || null;
                try {
                  buffer=gl.createBuffer();
                  if (!buffer) throw new Error('createBuffer returned null');
                  this.bindPhysicalElementBuffer(vao,buffer);
                  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,output,gl.STATIC_DRAW);
                } catch (error) {
                  this.bindPhysicalElementBuffer(vao,previousPhysicalElement);
                  if (buffer) {
                    this.forgetPhysicalElementBuffer(buffer);
                    try { gl.deleteBuffer(buffer); } catch (ignored) {}
                  }
                  if (stats) {
                    stats.baseVertexIndexUploadFailures=
                      (stats.baseVertexIndexUploadFailures||0)+1;
                    stats.baseVertexIndexUploadFailureReason=String(
                      error && (error.message||error.name)||error);
                  }
                  return null;
                }
                entry={buffer:buffer,type:outputType,count:length,bytes:output.byteLength,
                  min:minIndex,max:maxIndex,element:elementBuffer,version:version,inputType:type|0,
                  offset:start,inputCount:length,base:base,deleted:false,cacheKey:key};
                this.shiftedIndexCache.set(key,entry);
                this.registerBufferCacheKey(this.shiftedIndexCacheKeys,elementBuffer|0,key);
                this.shiftedIndexCacheTotalBytes=
                  (Number(this.shiftedIndexCacheTotalBytes)||0)+output.byteLength;
                this.updateShiftedIndexTelemetry();
                this.noteShiftedIndexCreated();
                vao.shiftedIndexLast=entry;
                if (!fastCache.has(fastKey) && fastCache.size >= 64) fastCache.clear();
                fastCache.set(fastKey,entry);
                this.trackShiftedIndexEntryVao(entry,vao);
                if (stats) {
                  stats.baseVertexIndexBuffers=(stats.baseVertexIndexBuffers||0)+1;
                  stats.baseVertexIndexBytes=(stats.baseVertexIndexBytes||0)+output.byteLength;
                  stats.baseVertexIndexLastMin=minIndex;
                  stats.baseVertexIndexLastMax=maxIndex;
                }
                return entry;
              };
              window.__gaiusGL.drawElementsWithBaseVertex=function(vao,mode,count,type,offset,instances,baseVertex) {
                const gl=window.__gaiusWebGL;
                const off=Number(offset);
                const inst=instances|0;
                const base=baseVertex|0;
                if (this.guiDrawDiagnostics && (this.guiDrawsRemaining|0)>0) {
                  this.sampleGuiDraw(mode,count,type,off,inst,base);
                }
                if (base === 0) {
                  this.ensureLogicalElementBuffer(vao);
                  if (inst > 1) {
                    gl.drawElementsInstanced(mode,count,type,off,inst);
                  } else {
                    gl.drawElements(mode,count,type,off);
                  }
                  return;
                }
                const extension=this.baseVertexExtensionChecked
                  ? this.baseVertexExtension : this.getBaseVertexExtension();
                if (extension && extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL) {
                  this.ensureLogicalElementBuffer(vao);
                  extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL(
                    mode,count,type,off,Math.max(1,inst),base,0);
                  if (this.hotPathTelemetryEnabled) {
                    var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.baseVertexExtensionDraws=(stats.baseVertexExtensionDraws||0)+1;
                  }
                  return;
                }
                if (extension && extension.drawElementsInstancedBaseVertexWEBGL) {
                  this.ensureLogicalElementBuffer(vao);
                  extension.drawElementsInstancedBaseVertexWEBGL(mode,count,type,off,Math.max(1,inst),base);
                  if (this.hotPathTelemetryEnabled) {
                    var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.baseVertexExtensionDraws=(stats.baseVertexExtensionDraws||0)+1;
                  }
                  return;
                }
                const shiftedIndex=this.cacheShiftedIndexBuffer(vao,type,off,count,base);
                if (shiftedIndex) {
                  this.bindPhysicalElementBuffer(vao,shiftedIndex.buffer);
                  if (inst > 1) {
                    gl.drawElementsInstanced(mode,count,shiftedIndex.type,0,inst);
                  } else {
                    gl.drawElements(mode,count,shiftedIndex.type,0);
                  }
                  if (this.hotPathTelemetryEnabled) {
                    var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.baseVertexIndexDraws=(stats.baseVertexIndexDraws||0)+1;
                  }
                  return;
                }
                this.ensureLogicalElementBuffer(vao);
                if (this.hotPathTelemetryEnabled) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.baseVertexIndexFallbacks=(stats.baseVertexIndexFallbacks||0)+1;
                }
                this.withBaseVertexAttribs(base,function() {
                  if (inst > 1) {
                    gl.drawElementsInstanced(mode,count,type,off,inst);
                  } else {
                    gl.drawElements(mode,count,type,off);
                  }
                });
              };
            }
            """)
    private static native void initializeDrawCompatibilityJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__attribTypeAdaptInit) return;
            state.__attribTypeAdaptInit=true;
            state.expectedAttribInteger=function(index) {
              const program=this.currentProgram|0;
              if (!program) return null;
              let attribs=this.programAttribs.get(program);
              if (!attribs) {
                this.refreshProgramAttribs(program);
                attribs=this.programAttribs.get(program);
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.attribPointerProgramLazyRefresh=(stats.attribPointerProgramLazyRefresh||0)+1;
              }
              if (!attribs) return null;
              const byLocation=attribs.byLocation;
              if (byLocation) {
                const mappedAttrib=byLocation.get(index|0);
                return mappedAttrib ? !!mappedAttrib.integer : null;
              }
              for (let i=0;i<attribs.length;i++) {
                const candidate=attribs[i];
                if (candidate && (candidate.location|0)===(index|0)) return !!candidate.integer;
              }
              return null;
            };
            state.recordAttribPointerAdapt=function(index,requestedInteger,effectiveInteger,type) {
              if (!!requestedInteger===!!effectiveInteger) return;
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.attribTypePointerAdapts=(stats.attribTypePointerAdapts||0)+1;
              stats.attribTypePointerAdaptLast={
                program:this.currentProgram|0,
                location:index|0,
                requestedInteger:!!requestedInteger,
                effectiveInteger:!!effectiveInteger,
                pointerType:type|0
              };
            };
            """)
    private static native void initializeAttribTypeAdaptJs();

    @JSBody(script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (!state || state.__elementBufferStateInit) return;
            state.__elementBufferStateInit=true;
            state.vaoEmu.forEach(function(vao) {
              if (vao && vao.actualElementArrayBuffer===undefined) {
                vao.actualElementArrayBuffer=null;
              }
              if (vao && vao.elementArrayBufferObject===undefined) {
                const id=vao.elementArrayBuffer|0;
                vao.elementArrayBufferObject=id ? state.buffers.get(id) : null;
              }
            });
            state.bindPhysicalElementBuffer=function(vao, buffer) {
              const next=buffer || null;
              if (vao.actualElementArrayBuffer===next) {
                if (this.hotPathTelemetryEnabled) {
                  this.physicalElementBufferBindSkips=
                    ((this.physicalElementBufferBindSkips||0)+1)|0;
                }
                return false;
              }
              gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,next);
              vao.actualElementArrayBuffer=next;
              if (this.hotPathTelemetryEnabled) {
                this.physicalElementBufferBinds=((this.physicalElementBufferBinds||0)+1)|0;
              }
              return true;
            };
            state.ensureLogicalElementBuffer=function(vao) {
              return this.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null);
            };
            state.forgetPhysicalElementBuffer=function(buffer) {
              if (!buffer) return;
              this.vaoEmu.forEach(function(vao) {
                if (vao && vao.actualElementArrayBuffer===buffer) {
                  vao.actualElementArrayBuffer=null;
                }
              });
            };
            """)
    private static native void initializeElementBufferStateJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__vaoFastPathInstalled) return;
            state.__vaoFastPathInstalled=true;
            state.currentVaoCacheId=-1;
            state.currentVaoCache=null;
            const getVaoEmu=state.getVaoEmu;
            state.getVaoEmu=function() {
              const id=this.currentVaoId|0;
              if ((this.currentVaoCacheId|0)===id && this.currentVaoCache) {
                return this.currentVaoCache;
              }
              const vao=getVaoEmu.call(this);
              this.currentVaoCacheId=id;
              this.currentVaoCache=vao;
              return vao;
            };
            """)
    private static native void initializeVaoFastPathJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__performanceStateInit) return;
            state.__performanceStateInit=true;
            let enabled=window.__gaiusHotPathTelemetry===true;
            try {
              const params=new URLSearchParams(location.search);
              const diag=(params.get('diag') || '').toLowerCase();
              enabled=enabled || params.get('glStats')==='1'
                || diag==='perf' || diag==='gl' || diag==='gui' || diag==='all';
            } catch (ignored) {}
            state.hotPathTelemetryEnabled=!!enabled;
            state.textureBufferDefaults=state.textureBufferDefaults || new Set();
            state.textureParameters=state.textureParameters || new Map();
            state.samplerBindings=state.samplerBindings || new Map();
            state.indexedBufferBindings=state.indexedBufferBindings || new Map();
            state.uniformPrograms=state.uniformPrograms || new Map();
            state.programUniformLocations=state.programUniformLocations || new Map();
            state.uniform1iValues=state.uniform1iValues || new Map();
            state.uniform1fValues=state.uniform1fValues || new Map();
            state.clearProgramUniforms=function(program) {
              if (!this.uniformPrograms || !this.uniformPrograms.size) return;
              const removed=[];
              this.uniformPrograms.forEach(function(owner,location) {
                if ((owner|0)===(program|0)) removed.push(location|0);
              });
              for (let i=0;i<removed.length;i++) {
                const location=removed[i]|0;
                this.uniformPrograms.delete(location);
                if (this.uniforms) this.uniforms.delete(location);
                this.uniform1iValues.delete(location);
                this.uniform1fValues.delete(location);
                if (this.uniformValueCache) this.uniformValueCache.delete(location);
              }
              this.programUniformLocations.delete(program|0);
            };
            const mask=state.colorMask || [true,true,true,true];
            state.colorMaskBits=(mask[0]?1:0)|(mask[1]?2:0)|(mask[2]?4:0)|(mask[3]?8:0);
            const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            if (!stats.textureInfo || Array.isArray(stats.textureInfo)) stats.textureInfo={};
            stats.hotPathTelemetryEnabled=state.hotPathTelemetryEnabled;
            state.updateBufferShadowTelemetry();
            state.updateShiftedIndexTelemetry();
            state.updateAlignedAttribTelemetry();
            """)
    private static native void initializePerformanceStateJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__gpuHotPathInit) return;
            state.__gpuHotPathInit=true;
            state.unpackAlignment=state.unpackAlignment===undefined ? 4 : state.unpackAlignment|0;
            state.unpackRowLength=state.unpackRowLength===undefined ? 0 : state.unpackRowLength|0;
            state.unpackSkipRows=state.unpackSkipRows===undefined ? 0 : state.unpackSkipRows|0;
            state.unpackSkipPixels=state.unpackSkipPixels===undefined ? 0 : state.unpackSkipPixels|0;
            state.uniformBufferPadScratch=null;
            state.prepareBufferDataUpload=function(data,padTo256) {
              if (!padTo256 || (data && data.byteLength>=256)) return data;
              let scratch=this.uniformBufferPadScratch;
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              if (!scratch) {
                scratch=new Int8Array(256);
                this.uniformBufferPadScratch=scratch;
                stats.uniformBufferPadScratchAllocations=
                  (stats.uniformBufferPadScratchAllocations||0)+1;
              } else {
                scratch.fill(0);
                stats.uniformBufferPadScratchReuses=
                  (stats.uniformBufferPadScratchReuses||0)+1;
              }
              if (data) scratch.set(data,0);
              return scratch;
            };
            state.noteBufferUpload=function(sourceBytes,uploadedBytes,subData) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              if (subData) stats.bufferSubDataCalls=(stats.bufferSubDataCalls||0)+1;
              else stats.bufferDataCalls=(stats.bufferDataCalls||0)+1;
              stats.bufferUploadSourceBytes=(stats.bufferUploadSourceBytes||0)+Number(sourceBytes||0);
              stats.bufferUploadBytes=(stats.bufferUploadBytes||0)+Number(uploadedBytes||0);
              const padding=Math.max(0,Number(uploadedBytes||0)-Number(sourceBytes||0));
              if (padding) stats.bufferUploadPaddingBytes=(stats.bufferUploadPaddingBytes||0)+padding;
            };
            state.noteNamedBufferBindings=function(physical,skipped) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              if (physical) stats.namedBufferPhysicalBinds=
                (stats.namedBufferPhysicalBinds||0)+(physical|0);
              if (skipped) stats.namedBufferBindSkips=
                (stats.namedBufferBindSkips||0)+(skipped|0);
            };
            state.applyTextureParameter=function(texture,target,parameter,value) {
              let parameters=this.textureParameters.get(texture|0);
              if (!parameters) {
                parameters=new Map();
                this.textureParameters.set(texture|0,parameters);
              } else if (parameters.has(parameter|0)
                  && (parameters.get(parameter|0)|0)===(value|0)) {
                const skipStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                skipStats.texBufferTextureParameterSkips=
                  (skipStats.texBufferTextureParameterSkips||0)+1;
                return false;
              }
              window.__gaiusWebGL.texParameteri(target,parameter,value);
              parameters.set(parameter|0,value|0);
              const callStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              callStats.texBufferTextureParameterCalls=
                (callStats.texBufferTextureParameterCalls||0)+1;
              return true;
            };
            """)
    private static native void initializeGpuHotPathJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__uniformValueCacheInit) return;
            state.__uniformValueCacheInit=true;
            state.uniformValueCache=new Map();
            state.noteUniformValueFastSkip=function() {
              if (!this.hotPathTelemetryEnabled) return;
              const skips=((this.uniformValueFastSkips||0)+1)|0;
              this.uniformValueFastSkips=skips;
              if ((skips & 255)===0) {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.uniformValueFastSkips=skips;
              }
            };
            state.uniformScalarsChanged=function(location,kind,count,x,y,z,w) {
              const key=location|0;
              let entry=this.uniformValueCache.get(key);
              let same=!!entry && (entry.kind|0)===(kind|0)
                && (entry.count|0)===(count|0) && entry.transpose===false;
              if (same) {
                const values=entry.values;
                same=Object.is(values[0],x)
                  && ((count|0)<2 || Object.is(values[1],y))
                  && ((count|0)<3 || Object.is(values[2],z))
                  && ((count|0)<4 || Object.is(values[3],w));
                if (same) {
                  this.noteUniformValueFastSkip();
                  return false;
                }
              }
              if (!entry || !entry.values || entry.values.length!==(count|0)) {
                entry={kind:kind|0,count:count|0,transpose:false,
                  values:new Float64Array(count|0)};
                this.uniformValueCache.set(key,entry);
              } else {
                entry.kind=kind|0;
                entry.count=count|0;
                entry.transpose=false;
              }
              entry.values[0]=x;
              if ((count|0)>1) entry.values[1]=y;
              if ((count|0)>2) entry.values[2]=z;
              if ((count|0)>3) entry.values[3]=w;
              this.uniform1iValues.delete(key);
              this.uniform1fValues.delete(key);
              return true;
            };
            state.uniformArrayChanged=function(location,kind,transpose,values) {
              if (!values) return true;
              const key=location|0;
              const length=values.length|0;
              const transposed=!!transpose;
              let entry=this.uniformValueCache.get(key);
              let same=!!entry && (entry.kind|0)===(kind|0)
                && (entry.count|0)===length && entry.transpose===transposed;
              if (same) {
                const cached=entry.values;
                for (let i=0;i<length;i++) {
                  if (!Object.is(cached[i],values[i])) {
                    same=false;
                    break;
                  }
                }
                if (same) {
                  this.noteUniformValueFastSkip();
                  return false;
                }
              }
              const integer=(kind|0)>=200 && (kind|0)<300;
              if (!entry || !entry.values || entry.values.length!==length
                  || (!!entry.integer)!==integer) {
                entry={kind:kind|0,count:length,transpose:transposed,integer:integer,
                  values:integer ? new Int32Array(length) : new Float32Array(length)};
                this.uniformValueCache.set(key,entry);
              } else {
                entry.kind=kind|0;
                entry.count=length;
                entry.transpose=transposed;
                entry.integer=integer;
              }
              for (let i=0;i<length;i++) entry.values[i]=values[i];
              this.uniform1iValues.delete(key);
              this.uniform1fValues.delete(key);
              return true;
            };
            """)
    private static native void initializeUniformValueCacheJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__drawStateCacheInit) return;
            state.__drawStateCacheInit=true;
            state.capabilityBit=function(capability) {
              switch (capability|0) {
                case 0x0B44: return 1;
                case 0x0C11: return 2;
                case 0x0BE2: return 4;
                case 0x0B71: return 8;
                default: return 0;
              }
            };
            state.enabledCapBits=0;
            state.enabledCaps.forEach(function(capability) {
              state.enabledCapBits|=state.capabilityBit(capability|0);
            });
            state.offscreen512Framebuffers=new Set();
            state.drawFramebufferOffscreen512=false;
            state.drawFramebufferOffscreenKnown=true;
            state.setDrawFramebufferCache=function(framebuffer) {
              const id=framebuffer|0;
              this.drawFramebufferOffscreen512=id!==0
                && this.offscreen512Framebuffers.has(id);
              this.drawFramebufferOffscreenKnown=id===0
                || this.framebufferColorTextures.has(id)
                || (this.framebufferColorTextureMisses
                  && this.framebufferColorTextureMisses.has(id));
            };
            state.refreshFramebufferOffscreen512=function(framebuffer) {
              const id=framebuffer|0;
              if (!id) return false;
              const texture=this.framebufferColorTextures.get(id)|0;
              const info=texture ? this.textureInfo.get(texture) : null;
              const matches=!!(info && (info.width|0)===512 && (info.height|0)===512);
              if (matches) this.offscreen512Framebuffers.add(id);
              else this.offscreen512Framebuffers.delete(id);
              if ((this.framebufferBindings.draw|0)===id) {
                this.setDrawFramebufferCache(id);
              }
              return matches;
            };
            state.refreshFramebuffersForTexture=function(texture) {
              const id=texture|0;
              if (!id) return;
              this.framebufferColorTextures.forEach(function(mapped,framebuffer) {
                if ((mapped|0)===id) this.refreshFramebufferOffscreen512(framebuffer|0);
              },this);
            };
            const findFramebufferColorTextureId=state.findFramebufferColorTextureId;
            state.findFramebufferColorTextureId=function(framebuffer) {
              const texture=findFramebufferColorTextureId.call(this,framebuffer|0)|0;
              this.refreshFramebufferOffscreen512(framebuffer|0);
              return texture;
            };
            state.isGuiItemOffscreen512Target=function() {
              const drawFramebuffer=this.framebufferBindings.draw|0;
              if (!drawFramebuffer) return false;
              if (!this.drawFramebufferOffscreenKnown) {
                this.findFramebufferColorTextureId(drawFramebuffer);
              }
              return !!this.drawFramebufferOffscreen512;
            };
            const recordTextureUpload=state.recordTextureUpload;
            state.recordTextureUpload=function(
                kind,target,level,x,y,width,height,internalFormat,format,type,pixels) {
              recordTextureUpload.call(
                this,kind,target,level,x,y,width,height,internalFormat,format,type,pixels);
              if (kind==='texImage2D' && (level|0)===0) {
                const texture=this.boundTextureId(target);
                if (texture) this.refreshFramebuffersForTexture(texture|0);
              }
            };
            state.setDrawFramebufferCache(state.framebufferBindings.draw|0);
            """)
    private static native void initializeDrawStateCacheJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state) throw new Error('Browser OpenGL state is not initialized');
            state.executeDraw=function(kind,mode,a,b,c,d,e,f) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const drawFramebuffer=this.framebufferBindings.draw|0;
                let disabled=null;
                let stats=null;
                let attribsChecked=false;
                const guiDraw=(this.guiDrawsRemaining|0)>0;
                const capBits=this.enabledCapBits|0;
                const beginGuiCullFaceBatch=guiDraw
                  && (capBits & 1)!==0
                  && !this.guiCullFaceBatchActive;
                const repairOffscreenScissor=drawFramebuffer!==0
                  && (capBits & 2)!==0
                  && (this.drawFramebufferOffscreenKnown
                    ? this.drawFramebufferOffscreen512
                    : this.isGuiItemOffscreen512Target());
                if (!repairOffscreenScissor) {
                  if (this.guiItemOffscreenScissorDisabled) {
                    this.restoreGuiItemOffscreenScissor('non-offscreen-draw');
                  }
                } else {
                  stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.offscreen512ScissorRepairs=(stats.offscreen512ScissorRepairs||0)+1;
                  if (!this.guiItemOffscreenScissorDisabled) {
                    gl.disable(gl.SCISSOR_TEST);
                    this.guiItemOffscreenScissorDisabled=true;
                    stats.offscreen512ScissorBatchDisables=(stats.offscreen512ScissorBatchDisables||0)+1;
                  }
                }
                if (!guiDraw && !repairOffscreenScissor) {
                  if (drawFramebuffer===0 && (this.colorMaskBits|0)!==15) {
                    gl.colorMask(true,true,true,true);
                    this.colorMask=[true,true,true,true];
                    this.colorMaskBits=15;
                    stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                    stats.defaultFramebufferColorMaskRepairs=
                      (stats.defaultFramebufferColorMaskRepairs||0)+1;
                  }
                  const drawGeneration=this.drawProgramGeneration|0;
                  const fastAttribsPrepared=(vao.drawReadyGeneration|0)===drawGeneration;
                  if (!fastAttribsPrepared) {
                    this.prepareDrawAttribs(vao);
                    attribsChecked=true;
                  }
                  const attribsReady=fastAttribsPrepared
                    || (vao.drawReadyGeneration|0)===drawGeneration;
                  if (attribsReady) {
                    if (this.hotPathTelemetryEnabled) {
                      if (fastAttribsPrepared) {
                        this.drawAttribPrepareFastSkips=((this.drawAttribPrepareFastSkips||0)+1)|0;
                        if ((this.drawAttribPrepareFastSkips & 1023)===0) {
                          stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                          stats.drawAttribPrepareFastSkips=this.drawAttribPrepareFastSkips;
                        }
                      }
                      this.recordDrawCall();
                    }
                    switch (kind|0) {
                      case 0: gl.drawArrays(mode,a|0,b|0); break;
                      case 1: this.ensureLogicalElementBuffer(vao); gl.drawElements(mode,a|0,b|0,Number(c)); break;
                      case 2: gl.drawArraysInstanced(mode,a|0,b|0,c|0); break;
                      case 3: this.ensureLogicalElementBuffer(vao); gl.drawElementsInstanced(mode,a|0,b|0,Number(c),d|0); break;
                      case 4: this.drawElementsWithBaseVertex(vao,mode,a|0,b|0,c,1,d|0); break;
                      case 5: this.drawElementsWithBaseVertex(vao,mode,a|0,b|0,c,d|0,e|0); break;
                      case 6: this.drawArraysWithBaseInstance(mode,a|0,b|0,c|0,d|0); break;
                      case 7: this.drawElementsWithBaseVertexBaseInstance(
                        vao,mode,a|0,b|0,c,d|0,e|0,f|0); break;
                      default: throw new Error('Unsupported browser draw kind: '+kind);
                    }
                    return;
                  }
                }
                let failed=true;
                try {
                  if (drawFramebuffer===0) {
                    if ((this.colorMaskBits|0)!==15) {
                      gl.colorMask(true,true,true,true);
                      this.colorMask=[true,true,true,true];
                      this.colorMaskBits=15;
                      stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                      stats.defaultFramebufferColorMaskRepairs=
                        (stats.defaultFramebufferColorMaskRepairs||0)+1;
                    }
                  }

                  const slowDrawGeneration=this.drawProgramGeneration|0;
                  const attribsPrepared=(vao.drawReadyGeneration|0)===slowDrawGeneration;
                  if (attribsPrepared) {
                    if (this.hotPathTelemetryEnabled) {
                      this.drawAttribPrepareFastSkips=((this.drawAttribPrepareFastSkips||0)+1)|0;
                      if ((this.drawAttribPrepareFastSkips & 1023)===0) {
                        stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                        stats.drawAttribPrepareFastSkips=this.drawAttribPrepareFastSkips;
                      }
                    }
                  } else if (!attribsChecked) {
                    this.prepareDrawAttribs(vao);
                    attribsChecked=true;
                  }

                  if (vao.missingEnabledAttribs && vao.missingEnabledAttribs.size) {
                    disabled=[];
                    vao.missingEnabledAttribs.forEach(function(attrib) {
                      const index=attrib|0;
                      gl.disableVertexAttribArray(index);
                      disabled.push(index);
                    });
                    if (disabled.length) {
                      stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                      stats.attribGuardDraws=(stats.attribGuardDraws||0)+1;
                      stats.attribGuardDisabled=(stats.attribGuardDisabled||0)+disabled.length;
                      stats.attribGuardLast=disabled.slice(0,16);
                    }
                  }
                  if (beginGuiCullFaceBatch) {
                    gl.disable(gl.CULL_FACE);
                    this.guiCullFaceBatchActive=true;
                    stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                    stats.guiCullFaceBatchDisables=(stats.guiCullFaceBatchDisables||0)+1;
                  }

                  if (this.hotPathTelemetryEnabled) this.recordDrawCall();
                  const guiDrawDiag=!!this.guiDrawDiagnostics && guiDraw;
                  const guiDrawState=guiDrawDiag ? this.captureGuiDrawState() : null;
                  switch (kind|0) {
                    case 0:
                      gl.drawArrays(mode,a|0,b|0);
                      break;
                    case 1:
                      this.ensureLogicalElementBuffer(vao);
                      gl.drawElements(mode,a|0,b|0,Number(c));
                      break;
                    case 2:
                      gl.drawArraysInstanced(mode,a|0,b|0,c|0);
                      break;
                    case 3:
                      this.ensureLogicalElementBuffer(vao);
                      gl.drawElementsInstanced(mode,a|0,b|0,Number(c),d|0);
                      break;
                    case 4:
                      this.drawElementsWithBaseVertex(vao,mode,a|0,b|0,c,1,d|0);
                      break;
                    case 5:
                      this.drawElementsWithBaseVertex(vao,mode,a|0,b|0,c,d|0,e|0);
                      break;
                    case 6:
                      this.drawArraysWithBaseInstance(mode,a|0,b|0,c|0,d|0);
                      break;
                    case 7:
                      this.drawElementsWithBaseVertexBaseInstance(
                        vao,mode,a|0,b|0,c,d|0,e|0,f|0);
                      break;
                    default:
                      throw new Error('Unsupported browser draw kind: '+kind);
                  }
                  if (guiDrawDiag) {
                    this.recordGuiDrawState(guiDrawState,gl.getError()|0);
                  }
                  failed=false;
                } finally {
                  if (guiDraw && (this.guiDrawsRemaining|0)>0) {
                    this.guiDrawsRemaining=Math.max(0,(this.guiDrawsRemaining|0)-1);
                    if ((this.guiDrawsRemaining|0)===0 && this.guiCullFaceBatchActive) {
                      gl.enable(gl.CULL_FACE);
                      this.guiCullFaceBatchActive=false;
                      stats=stats || (window.__gaiusGLStats || (window.__gaiusGLStats={}));
                      stats.guiCullFaceBatchRestores=(stats.guiCullFaceBatchRestores||0)+1;
                    }
                  }
                  if (disabled) {
                    for (let i=0;i<disabled.length;i++) {
                      gl.enableVertexAttribArray(disabled[i]);
                    }
                  }
                  if (failed && repairOffscreenScissor) {
                    this.restoreGuiItemOffscreenScissor('exception');
                  }
                }
              };
            """)
    private static native void initializeDrawFastPathJs();

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (!state || state.__baseInstanceDrawInit) return;
            state.__baseInstanceDrawInit=true;
            state.drawArraysWithBaseInstance=function(
                mode,first,count,instances,baseInstance) {
              const gl=window.__gaiusWebGL;
              const extension=this.baseVertexExtensionChecked
                ? this.baseVertexExtension : this.getBaseVertexExtension();
              if (extension && extension.drawArraysInstancedBaseInstanceWEBGL) {
                extension.drawArraysInstancedBaseInstanceWEBGL(
                  mode,first|0,count|0,Math.max(1,instances|0),baseInstance|0);
                return;
              }
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.baseInstanceFallbackDraws=(stats.baseInstanceFallbackDraws||0)+1;
              gl.drawArraysInstanced(mode,first|0,count|0,Math.max(1,instances|0));
            };
            state.drawElementsWithBaseVertexBaseInstance=function(
                vao,mode,count,type,offset,instances,baseVertex,baseInstance) {
              const extension=this.baseVertexExtensionChecked
                ? this.baseVertexExtension : this.getBaseVertexExtension();
              if (extension && extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL) {
                this.ensureLogicalElementBuffer(vao);
                extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL(
                  mode,count|0,type|0,Number(offset),Math.max(1,instances|0),
                  baseVertex|0,baseInstance|0);
                return;
              }
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.baseInstanceFallbackDraws=(stats.baseInstanceFallbackDraws||0)+1;
              this.drawElementsWithBaseVertex(
                vao,mode,count,type,offset,instances,baseVertex);
            };
            """)
    private static native void initializeBaseInstanceDrawJs();

    @JSBody(params = {"releaseMappedBuffers"}, script = """
            const state=window.__gaiusGL,gl=window.__gaiusWebGL;
            if (!state || !gl || state.__gpuFenceLifecycleInit) return;
            state.__gpuFenceLifecycleInit=true;
            state.gpuRetireFrameId=0;
            state.gpuRetireRecent=[];
            state.gpuFenceMeta=new Map();
            state.gpuCurrentRetireEntry=null;
            state.gpuNextFenceRetireOwned=false;
            const initiallyLost=!!(gl.isContextLost && gl.isContextLost());
            state.gpuContextLost=false;
            state.gpuReloadScheduled=false;
            state.gpuReloadStarted=false;
            state.gpuSubmissionBlocked=false;
            state.gpuContextLossController=null;
            try {
              state.gpuContextLossController=gl.getExtension
                ? gl.getExtension('WEBGL_lose_context') : null;
            } catch (ignored) {}
            state.gpuMaxClientWaitTimeout=0;
            try {
              state.gpuMaxClientWaitTimeout=Math.max(
                0,Number(gl.getParameter(gl.MAX_CLIENT_WAIT_TIMEOUT_WEBGL))||0);
            } catch (ignored) {}
            const initialStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            initialStats.gpuEarlyResourceReuse=Number(initialStats.gpuEarlyResourceReuse)||0;
            initialStats.gpuFenceTimeouts=Number(initialStats.gpuFenceTimeouts)||0;
            initialStats.gpuFenceDuplicateDeletes=
              Number(initialStats.gpuFenceDuplicateDeletes)||0;
            initialStats.gpuWaitFailures=Number(initialStats.gpuWaitFailures)||0;
            initialStats.gpuContextLosses=Number(initialStats.gpuContextLosses)||0;
            initialStats.gpuRetireControlledErrors=
              Number(initialStats.gpuRetireControlledErrors)||0;
            initialStats.gpuContextRecovery='ready';
            state.gpuOldestFenceAge=function() {
              let oldest=0;
              this.gpuFenceMeta.forEach(function(meta) {
                oldest=Math.max(oldest,Math.max(0,(this.gpuRetireFrameId|0)-(meta.createdFrame|0)));
              },this);
              return oldest|0;
            };
            state.gpuPublishRetire=function(backlog,capacity) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              const active=Math.max(0,backlog|0);
              stats.gpuRetireBacklog=active;
              stats.gpuRetireCapacity=Math.max(0,capacity|0);
              stats.gpuRetireBacklogMax=Math.max(stats.gpuRetireBacklogMax||0,active);
              stats.gpuFenceAgeFrames=this.gpuOldestFenceAge();
              stats.gpuFenceMaxAgeFrames=Math.max(
                stats.gpuFenceMaxAgeFrames||0,stats.gpuFenceAgeFrames|0);
              stats.gpuRetireRecent=this.gpuRetireRecent;
              stats.gpuContextLost=!!this.gpuContextLost;
            };
            state.gpuBeginRetireFrame=function(backlog,capacity) {
              this.gpuRetireFrameId=((this.gpuRetireFrameId|0)+1)|0;
              if (this.gpuRetireFrameId<=0) this.gpuRetireFrameId=1;
              const entry={
                frame:this.gpuRetireFrameId|0,
                at:(typeof performance!=='undefined' && performance.now)
                  ? performance.now() : Date.now(),
                backlogBefore:Math.max(0,backlog|0),
                backlogAfter:Math.max(0,backlog|0),
                capacity:Math.max(0,capacity|0),
                oldestFenceAgeFrames:this.gpuOldestFenceAge(),
                waits:0,
                timeouts:0,
                waitFailures:0,
                signaled:0,
                deleted:0,
                backpressure:false
              };
              this.gpuCurrentRetireEntry=entry;
              this.gpuRetireRecent.push(entry);
              if (this.gpuRetireRecent.length>120) {
                this.gpuRetireRecent.splice(0,this.gpuRetireRecent.length-120);
              }
              this.gpuPublishRetire(backlog,capacity);
            };
            state.gpuEndRetireFrame=function(backlog,backpressure) {
              const entry=this.gpuCurrentRetireEntry;
              if (entry) {
                entry.backlogAfter=Math.max(0,backlog|0);
                entry.oldestFenceAgeFrames=this.gpuOldestFenceAge();
                entry.backpressure=!!backpressure;
              }
              if (backpressure) {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.gpuRetireBackpressureFrames=(stats.gpuRetireBackpressureFrames||0)+1;
              }
              this.gpuPublishRetire(backlog,entry ? entry.capacity|0 : 0);
              this.gpuCurrentRetireEntry=null;
            };
            state.gpuRecordFenceCreated=function(id,retireOwned) {
              this.gpuFenceMeta.set(id|0,{
                createdFrame:this.gpuRetireFrameId|0,
                timeouts:0,
                failures:0,
                signaled:false,
                retireOwned:!!retireOwned
              });
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.gpuFencesCreated=(stats.gpuFencesCreated||0)+1;
              if (this.gpuCurrentRetireEntry) this.gpuCurrentRetireEntry.created=true;
            };
            state.gpuRecordFenceCreateFailure=function(reason) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.gpuFenceCreateFailures=(stats.gpuFenceCreateFailures||0)+1;
              stats.gpuFenceCreateFailureReason=String(reason||'unknown');
              if (this.gpuCurrentRetireEntry) {
                this.gpuCurrentRetireEntry.createFailure=stats.gpuFenceCreateFailureReason;
              }
            };
            state.gpuRecordFenceWait=function(
                id,status,requestedTimeout,maxTimeout,contextLost,reason) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              const meta=this.gpuFenceMeta.get(id|0);
              const age=meta
                ? Math.max(0,(this.gpuRetireFrameId|0)-(meta.createdFrame|0)) : 0;
              stats.gpuFenceWaits=(stats.gpuFenceWaits||0)+1;
              stats.gpuFenceAgeFrames=age;
              stats.gpuFenceMaxAgeFrames=Math.max(stats.gpuFenceMaxAgeFrames||0,age);
              stats.gpuFenceLastRequestedTimeoutNs=Math.max(0,Number(requestedTimeout)||0);
              stats.gpuFenceMaxClientWaitTimeoutNs=Math.max(0,Number(maxTimeout)||0);
              if (Number(requestedTimeout)>0) {
                stats.gpuFenceForcedPolls=(stats.gpuFenceForcedPolls||0)+1;
              }
              const entry=this.gpuCurrentRetireEntry;
              if (entry) {
                entry.waits=(entry.waits|0)+1;
                entry.lastWaitStatus=status|0;
                entry.lastFenceAgeFrames=age;
              }
              if ((status|0)===0x911B) {
                stats.gpuFenceTimeouts=(stats.gpuFenceTimeouts||0)+1;
                if (meta) meta.timeouts=(meta.timeouts|0)+1;
                if (entry) entry.timeouts=(entry.timeouts|0)+1;
                return;
              }
              if ((status|0)===0x911A || (status|0)===0x911C) {
                stats.gpuFencesSignaled=(stats.gpuFencesSignaled||0)+1;
                if (meta) meta.signaled=true;
                if (entry) entry.signaled=(entry.signaled|0)+1;
                return;
              }
              stats.gpuWaitFailures=(stats.gpuWaitFailures||0)+1;
              stats.gpuWaitFailureReason=String(reason||'wait-failed');
              if (meta) meta.failures=(meta.failures|0)+1;
              if (entry) entry.waitFailures=(entry.waitFailures|0)+1;
              if (contextLost) {
                stats.gpuContextLossWaits=(stats.gpuContextLossWaits||0)+1;
              }
              const failures=meta ? meta.failures|0 : stats.gpuWaitFailures|0;
              if (failures===1) {
                console.error('[Gaius] GPU fence wait failed; retaining transient resources',
                  stats.gpuWaitFailureReason);
              }
              if (failures>=120 && !stats.gpuRetireControlledError) {
                stats.gpuRetireControlledError={
                  frame:this.gpuRetireFrameId|0,
                  fence:id|0,
                  ageFrames:age,
                  reason:stats.gpuWaitFailureReason
                };
                stats.gpuRetireControlledErrors=(stats.gpuRetireControlledErrors||0)+1;
                console.error('[Gaius] GPU retire remains unavailable; resources stay retained',
                  stats.gpuRetireControlledError);
              }
            };
            state.gpuRecordFenceDeleted=function(id) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              const meta=this.gpuFenceMeta.get(id|0);
              if (!meta) {
                stats.gpuFenceDuplicateDeletes=(stats.gpuFenceDuplicateDeletes||0)+1;
                return;
              }
              if (meta.retireOwned && !meta.signaled) {
                stats.gpuEarlyResourceReuse=(stats.gpuEarlyResourceReuse||0)+1;
              }
              const age=Math.max(0,(this.gpuRetireFrameId|0)-(meta.createdFrame|0));
              this.gpuFenceMeta.delete(id|0);
              stats.gpuFencesDeleted=(stats.gpuFencesDeleted||0)+1;
              stats.gpuFenceLastRetiredAgeFrames=age;
              if (this.gpuCurrentRetireEntry) {
                this.gpuCurrentRetireEntry.deleted=(this.gpuCurrentRetireEntry.deleted|0)+1;
              }
            };
            state.gpuRecordRetireClose=function(pending) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.gpuRetireClosePending=Math.max(0,pending|0);
              if ((pending|0)>0) {
                stats.gpuRetireCloseDeferrals=(stats.gpuRetireCloseDeferrals||0)+1;
              } else {
                stats.gpuRetireCleanCloses=(stats.gpuRetireCleanCloses||0)+1;
              }
            };
            state.gpuMarkContextLost=function(reason) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              if (!this.gpuContextLost) {
                stats.gpuContextLosses=(stats.gpuContextLosses||0)+1;
                console.error('[Gaius] WebGL context lost; GPU retire and reuse are paused');
              }
              this.gpuContextLost=true;
              stats.gpuContextLost=true;
              stats.gpuContextLossReason=String(reason||'unknown');
              stats.gpuContextRecovery='reload-required';
              this.gpuReleaseMappedBuffers('context-loss');
              this.gpuBlockSubmissions('context-lost-stale-objects');
              this.gpuScheduleReload(reason||'context-loss');
            };
            state.gpuReleaseMappedBuffers=function(reason) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.gpuMappedBufferReleaseRequests=
                (stats.gpuMappedBufferReleaseRequests||0)+1;
              stats.gpuMappedBufferReleaseReason=String(reason||'unknown');
              if (typeof releaseMappedBuffers!=='function') {
                stats.gpuMappedBufferReleaseState='unavailable';
                return false;
              }
              try {
                releaseMappedBuffers();
                stats.gpuMappedBufferReleaseState='released';
                stats.gpuMappedBufferReleaseSuccesses=
                  (stats.gpuMappedBufferReleaseSuccesses||0)+1;
                return true;
              } catch (error) {
                stats.gpuMappedBufferReleaseState='failed';
                stats.gpuMappedBufferReleaseFailures=
                  (stats.gpuMappedBufferReleaseFailures||0)+1;
                stats.gpuMappedBufferReleaseFailure=String(
                  error && (error.stack||error.message)||error);
                return false;
              }
            };
            state.gpuBlockSubmissions=function(reason) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.gpuSubmissionBlocked=true;
              stats.gpuSubmissionBlockReason=String(reason||'unknown');
              if (this.gpuSubmissionBlocked) return;
              this.gpuSubmissionBlocked=true;
              const blockedMethods=new Map();
              const blocked=function(name,target) {
                if (blockedMethods.has(name)) return blockedMethods.get(name);
                const replacement=function() {
                  stats.gpuBlockedCalls=(stats.gpuBlockedCalls||0)+1;
                  stats.gpuLastBlockedCall=String(name);
                  if (name==='isContextLost') return true;
                  if (name==='getError') return target.CONTEXT_LOST_WEBGL||0x9242;
                  if (name==='getSupportedExtensions') return [];
                  return null;
                };
                blockedMethods.set(name,replacement);
                return replacement;
              };
              const names=new Set();
              let prototype=gl;
              while (prototype && prototype!==Object.prototype) {
                Object.getOwnPropertyNames(prototype).forEach(function(name) {
                  if (name!=='constructor') names.add(name);
                });
                prototype=Object.getPrototypeOf(prototype);
              }
              let patched=0,failed=0;
              names.forEach(function(name) {
                let value;
                try { value=gl[name]; } catch (ignored) { return; }
                if (typeof value!=='function') return;
                try {
                  Object.defineProperty(gl,name,{
                    configurable:true,
                    value:blocked(name,gl),
                    writable:false
                  });
                  patched++;
                } catch (ignored) {
                  failed++;
                }
              });
              stats.gpuBlockedMethodCount=patched;
              stats.gpuUnblockedMethodCount=failed;
              if (failed>0 && typeof Proxy==='function') {
                try {
                  window.__gaiusWebGL=new Proxy(gl,{
                    get(target,name) {
                      const value=Reflect.get(target,name,target);
                      return typeof value==='function' ? blocked(name,target) : value;
                    }
                  });
                  stats.gpuSubmissionBlockFallback='proxy';
                } catch (ignored) {
                  stats.gpuSubmissionBlockFallback='failed';
                }
              }
              if (failed>0 && this.gpuContextLossController
                  && typeof this.gpuContextLossController.loseContext==='function') {
                try {
                  this.gpuContextLossController.loseContext();
                  stats.gpuSubmissionContextQuarantined=true;
                } catch (ignored) {
                  stats.gpuSubmissionContextQuarantined=false;
                }
              }
            };
            state.gpuScheduleReload=function(reason) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              if (window.__gaiusDisableGpuContextAutoReload===true) {
                stats.gpuContextRecovery='reload-required';
                return false;
              }
              if (this.gpuReloadScheduled) return false;
              this.gpuReloadScheduled=true;
              stats.gpuContextRecovery='reload-scheduled';
              stats.gpuReloadReason=String(reason||'unknown');
              setTimeout(function() {
                if (state.gpuReloadStarted) return;
                state.gpuReloadStarted=true;
                state.gpuReleaseMappedBuffers('before-reload');
                stats.gpuContextRecovery='reloading';
                try {
                  if (window.location && typeof window.location.reload==='function') {
                    window.location.reload();
                  } else {
                    stats.gpuContextRecovery='reload-required';
                  }
                } catch (error) {
                  stats.gpuContextRecovery='reload-failed';
                  stats.gpuReloadFailure=String(
                    error && (error.stack||error.message)||error);
                }
              },0);
              return true;
            };
            const canvas=gl.canvas;
            if (canvas && canvas.addEventListener) {
              canvas.addEventListener('webglcontextlost',function(event) {
                if (event && event.preventDefault) event.preventDefault();
                state.gpuMarkContextLost('webglcontextlost');
              },false);
              canvas.addEventListener('webglcontextrestored',function() {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                state.gpuContextLost=true;
                stats.gpuContextLost=true;
                stats.gpuContextRestores=(stats.gpuContextRestores||0)+1;
                state.gpuReleaseMappedBuffers('context-restored');
                state.gpuBlockSubmissions('context-restored-stale-objects');
                state.gpuScheduleReload('webglcontextrestored');
              },false);
            }
            if (initiallyLost) state.gpuMarkContextLost('initialization');
            state.gpuPublishRetire(0,0);
            """)
    private static native void initializeGpuFenceLifecycleJs(
            MappedBufferReleaseCallback releaseMappedBuffers);

    @JSBody(params = {"backlog", "capacity"}, script = """
            const state=window.__gaiusGL;
            if (state && state.gpuBeginRetireFrame) {
              state.gpuBeginRetireFrame(backlog|0,capacity|0);
            }
            """)
    public static native void beginGpuRetireFrame(int backlog, int capacity);

    @JSBody(params = {"backlog", "backpressure"}, script = """
            const state=window.__gaiusGL;
            if (state && state.gpuEndRetireFrame) {
              state.gpuEndRetireFrame(backlog|0,!!backpressure);
            }
            """)
    public static native void endGpuRetireFrame(int backlog, boolean backpressure);

    @JSBody(params = {"pending"}, script = """
            const state=window.__gaiusGL;
            if (state && state.gpuRecordRetireClose) {
              state.gpuRecordRetireClose(pending|0);
            }
            """)
    public static native void gpuRetireClose(int pending);

    public static void initialize() {
        initializeJs();
        initializeDrawCompatibilityJs();
        initializeAttribTypeAdaptJs();
        initializeElementBufferStateJs();
        initializePerformanceStateJs();
        initializeGpuHotPathJs();
        initializeUniformValueCacheJs();
        initializeDrawStateCacheJs();
        initializeVaoFastPathJs();
        initializeBaseInstanceDrawJs();
        initializeDrawFastPathJs();
        initializeGpuFenceLifecycleJs(BrowserOpenGL::releaseAllMappedBuffers);
        initializeShadowDecisionCache();
        initializeMisalignedBufferRefs();
        initializeVaoBufferRefsJs();
    }

    @JSBody(script = """
            const s=window.__gaiusGL,g=window.__gaiusWebGL;
            if(!s||s.__shadowDecisionInit)return;
            s.__shadowDecisionInit=true;
            s.shouldShadowBufferTarget=function(t,b){
              const id=b|0;
              if(!id)return false;
              if(t===g.ELEMENT_ARRAY_BUFFER){
                return this.bufferBytes.has(id)||!this.hasUsableBaseVertexExtension();
              }
              if(this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id))return true;
              if(t===0x8C2A||t===g.COPY_READ_BUFFER||t===g.COPY_WRITE_BUFFER)return true;
              if(t!==g.ARRAY_BUFFER)return false;
              const refs=this.misalignedBufferRefs;
              return refs ? ((refs.get(id)||0)>0) : this.bufferNeedsArrayShadow(id);
            };
            """)
    private static native void initializeShadowDecisionCache();

    @JSBody(script = """
            var s=window.__gaiusGL;
            if(!s||s.__mbrInit)return;
            s.__mbrInit=true;
            s.misalignedBufferRefs=new Map();
            const old=s.newVaoEmu;
            s.newVaoEmu=function(){const v=old.call(this);v.misalignedAttribBuffers=new Map();return v;};
            s.vaoEmu.forEach(function(v){
              if(!v)return;
              if(!v.misalignedAttribBuffers)v.misalignedAttribBuffers=new Map();
              if(!v.misalignedAttribs||!v.misalignedAttribs.size)return;
              v.misalignedAttribs.forEach(function(a){
                const i=a|0,p=v.attribPointers&&v.attribPointers.get(i);
                const b=p?(p.buffer|0):0;
                if(!b||v.misalignedAttribBuffers.has(i))return;
                v.misalignedAttribBuffers.set(i,b);
                const n=(s.misalignedBufferRefs.get(b)||0)|0;
                s.misalignedBufferRefs.set(b,(n+1)|0);
              });
            });
            s.addMbr=function(b){b|=0;if(!b)return;const p=(this.misalignedBufferRefs.get(b)||0)|0;this.misalignedBufferRefs.set(b,(p+1)|0);};
            s.delMbr=function(b){b|=0;if(!b)return;const n=((this.misalignedBufferRefs.get(b)||0)-1)|0;if(n>0)this.misalignedBufferRefs.set(b,n);else this.misalignedBufferRefs.delete(b);};
            s.setAttribMisaligned=function(v,i,m,b){i|=0;const had=v.misalignedAttribs.has(i),p=v.misalignedAttribBuffers.get(i)|0,n=m?((b==null?((v.attribPointers.get(i)||{}).buffer|0):b)|0):0;if(p&&p!==n)this.delMbr(p);if(m){v.misalignedAttribs.add(i);if(n&&p!==n)this.addMbr(n);if(n)v.misalignedAttribBuffers.set(i,n);}else{v.misalignedAttribs.delete(i);v.misalignedAttribBuffers.delete(i);}if(had!==!!m)v.drawReadyGeneration=-1;};
            s.releaseVaoMisalignedBuffers=function(v){if(!v||!v.misalignedAttribBuffers)return;v.misalignedAttribBuffers.forEach(this.delMbr,this);v.misalignedAttribBuffers.clear();if(v.misalignedAttribs)v.misalignedAttribs.clear();};
            s.bufferNeedsArrayShadow=function(buffer){if(!buffer)return false;const id=buffer|0;if(this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id))return true;const refs=this.misalignedBufferRefs;if(refs)return((refs.get(id)||0)>0);let n=false;this.vaoEmu.forEach(function(v){if(n||!v||!v.misalignedAttribs||!v.misalignedAttribs.size)return;v.misalignedAttribs.forEach(function(a){const p=v.attribPointers&&v.attribPointers.get(a|0);if(p&&(p.buffer|0)===id)n=true;});});return n;};
            """)
    private static native void initializeMisalignedBufferRefs();

    @JSBody(script = """
            const s=window.__gaiusGL;
            if(!s||s.__vaoBufferRefsInit)return;
            s.__vaoBufferRefsInit=true;
            s.vaoBufferRefs=new Map();
            s.physicalElementBufferVaoRefs=new Map();
            s.assignVaoIdentity=function(v,id){
              if(!v)return v;
              v.gaiusVaoId=id|0;
              if(!v.bufferRefCounts)v.bufferRefCounts=new Map();
              return v;
            };
            s.addVaoBufferRef=function(v,b){
              b|=0;
              if(!v||!b)return;
              var id=v.gaiusVaoId|0;
              if(id<0)return;
              var counts=v.bufferRefCounts||(v.bufferRefCounts=new Map());
              var previous=(counts.get(b)||0)|0;
              counts.set(b,(previous+1)|0);
              if(previous>0)return;
              var refs=this.vaoBufferRefs.get(b);
              if(!refs){refs=new Set();this.vaoBufferRefs.set(b,refs);}
              refs.add(id);
            };
            s.removeVaoBufferRef=function(v,b){
              b|=0;
              if(!v||!b||!v.bufferRefCounts)return;
              var previous=(v.bufferRefCounts.get(b)||0)|0;
              if(previous>1){v.bufferRefCounts.set(b,(previous-1)|0);return;}
              v.bufferRefCounts.delete(b);
              var refs=this.vaoBufferRefs.get(b);
              if(!refs)return;
              refs.delete(v.gaiusVaoId|0);
              if(!refs.size)this.vaoBufferRefs.delete(b);
            };
            s.replaceVaoBufferRef=function(v,previous,next){
              var oldId=previous|0,newId=next|0;
              if(oldId===newId)return;
              if(oldId)this.removeVaoBufferRef(v,oldId);
              if(newId)this.addVaoBufferRef(v,newId);
            };
            s.releaseVaoBufferRefs=function(v){
              if(!v||!v.bufferRefCounts)return;
              var id=v.gaiusVaoId|0;
              v.bufferRefCounts.forEach(function(_count,b){
                var refs=this.vaoBufferRefs.get(b|0);
                if(!refs)return;
                refs.delete(id);
                if(!refs.size)this.vaoBufferRefs.delete(b|0);
              },this);
              v.bufferRefCounts.clear();
            };
            s.addPhysicalElementBufferRef=function(v,b){
              if(!v||!b)return;
              var refs=this.physicalElementBufferVaoRefs.get(b);
              if(!refs){refs=new Set();this.physicalElementBufferVaoRefs.set(b,refs);}
              refs.add(v.gaiusVaoId|0);
            };
            s.removePhysicalElementBufferRef=function(v,b){
              if(!v||!b)return;
              var refs=this.physicalElementBufferVaoRefs.get(b);
              if(!refs)return;
              refs.delete(v.gaiusVaoId|0);
              if(!refs.size)this.physicalElementBufferVaoRefs.delete(b);
            };
            s.releaseVaoPhysicalElementBuffer=function(v){
              if(v&&v.actualElementArrayBuffer){
                this.removePhysicalElementBufferRef(v,v.actualElementArrayBuffer);
              }
            };
            var oldNewVao=s.newVaoEmu;
            s.newVaoEmu=function(){
              return this.assignVaoIdentity(oldNewVao.call(this),-1);
            };
            var oldGetVao=s.getVaoEmu;
            s.getVaoEmu=function(){
              return this.assignVaoIdentity(oldGetVao.call(this),this.currentVaoId|0);
            };
            var oldBindPhysical=s.bindPhysicalElementBuffer;
            s.bindPhysicalElementBuffer=function(v,b){
              var previous=v.actualElementArrayBuffer||null;
              var changed=oldBindPhysical.call(this,v,b);
              if(changed){
                if(previous)this.removePhysicalElementBufferRef(v,previous);
                if(b)this.addPhysicalElementBufferRef(v,b);
              }
              return changed;
            };
            s.forgetPhysicalElementBuffer=function(buffer){
              if(!buffer)return;
              var refs=this.physicalElementBufferVaoRefs.get(buffer);
              if(!refs)return;
              var ids=Array.from(refs);
              for(var i=0;i<ids.length;i++){
                var v=this.vaoEmu.get(ids[i]|0);
                if(v&&v.actualElementArrayBuffer===buffer)v.actualElementArrayBuffer=null;
              }
              this.physicalElementBufferVaoRefs.delete(buffer);
            };
            s.vaoEmu.forEach(function(v,id){
              this.assignVaoIdentity(v,id|0);
              var element=v.elementArrayBuffer|0;
              if(element)this.addVaoBufferRef(v,element);
              if(v.attribPointers)v.attribPointers.forEach(function(p){
                var pointerBuffer=p?(p.buffer|0):0;
                if(pointerBuffer)this.addVaoBufferRef(v,pointerBuffer);
              },this);
              if(v.vertexBuffers)v.vertexBuffers.forEach(function(binding){
                var vertexBuffer=binding?(binding.buffer|0):0;
                if(vertexBuffer)this.addVaoBufferRef(v,vertexBuffer);
              },this);
              if(v.actualElementArrayBuffer){
                this.addPhysicalElementBufferRef(v,v.actualElementArrayBuffer);
              }
            },s);
            """)
    private static native void initializeVaoBufferRefsJs();

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            const state=window.__gaiusGL;
            const bit=state ? state.capabilityBit(capability|0) : 0;
            if (state && capability === window.__gaiusWebGL.SCISSOR_TEST
                && state.guiItemOffscreenScissorDisabled) {
              state.restoreGuiItemOffscreenScissor('enable');
            }
            if (state && state.knownCaps.has(capability|0) && state.enabledCaps.has(capability|0)) {
              if (bit) state.enabledCapBits|=bit;
              return;
            }
            if (state) {
              state.knownCaps.add(capability|0);
              state.enabledCaps.add(capability|0);
              if (bit) state.enabledCapBits|=bit;
            }
            window.__gaiusWebGL.enable(capability);
            """)
    public static native void enable(int capability);

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            const state=window.__gaiusGL;
            const bit=state ? state.capabilityBit(capability|0) : 0;
            const physicallyDisabled=!!(state
              && capability === window.__gaiusWebGL.SCISSOR_TEST
              && state.guiItemOffscreenScissorDisabled);
            if (physicallyDisabled) {
              state.guiItemOffscreenScissorDisabled=false;
            }
            if (state && state.knownCaps.has(capability|0) && !state.enabledCaps.has(capability|0)) {
              if (bit) state.enabledCapBits&=~bit;
              return;
            }
            if (state) {
              state.knownCaps.add(capability|0);
              state.enabledCaps.delete(capability|0);
              if (bit) state.enabledCapBits&=~bit;
            }
            if (!physicallyDisabled) window.__gaiusWebGL.disable(capability);
            """)
    public static native void disable(int capability);

    @JSBody(params = {"red", "green", "blue", "alpha"}, script = """
            const state=window.__gaiusGL;
            if (state && state.clearColorR===red && state.clearColorG===green
                && state.clearColorB===blue && state.clearColorA===alpha) return;
            if (state) {
              state.clearColorR=red; state.clearColorG=green;
              state.clearColorB=blue; state.clearColorA=alpha;
            }
            window.__gaiusWebGL.clearColor(red,green,blue,alpha);
            """)
    public static native void clearColor(float red, float green, float blue, float alpha);

    @JSBody(params = {"depth"}, script = """
            const state=window.__gaiusGL;
            if (state && state.clearDepthValue===depth) return;
            if (state) state.clearDepthValue=depth;
            window.__gaiusWebGL.clearDepth(depth);
            """)
    public static native void clearDepth(double depth);

    @JSBody(params = {"mask"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (state && ((mask|0) & gl.COLOR_BUFFER_BIT)) {
              const framebuffer=state.framebufferBindings.draw|0;
              if (framebuffer) {
                const texture=state.findFramebufferColorTextureId(framebuffer);
                if (texture) state.invalidateGuiItemAtlasBlitCache(texture,'clear');
              }
            }
            gl.clear(mask);
            """)
    public static native void clear(int mask);

    public static void clearBufferfv(int buffer, int drawBuffer, FloatBuffer values) {
        clearBufferfvJs(buffer, drawBuffer, floats(values));
    }

    public static void clearBufferfv(int buffer, int drawBuffer, float[] values) {
        clearBufferfvJs(buffer, drawBuffer, Float32Array.fromJavaArray(values));
    }

    public static void clearBufferfv(int buffer, int drawBuffer, long address) {
        int valueCount = buffer == 0x1800 ? 4 : 1;
        clearBufferfvJs(buffer, drawBuffer, pointerFloats(address, valueCount));
    }

    @JSBody(params = {"buffer", "drawBuffer", "values"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if ((buffer|0)===(gl.COLOR|0) && state) {
              const framebuffer=state.framebufferBindings.draw|0;
              if (framebuffer) {
                const texture=state.findFramebufferColorTextureId(framebuffer);
                if (texture) state.invalidateGuiItemAtlasBlitCache(texture,'clearBufferfv');
              }
            }
            gl.clearBufferfv(buffer,drawBuffer,values);
            """)
    private static native void clearBufferfvJs(
            int buffer, int drawBuffer, Float32Array values);

    @JSBody(params = {"red", "green", "blue", "alpha"}, script = """
            const state=window.__gaiusGL;
            const bits=(red?1:0)|(green?2:0)|(blue?4:0)|(alpha?8:0);
            if (state && (state.colorMaskBits|0)===bits) return;
            if (state) {
              state.colorMaskBits=bits;
              state.colorMask=[!!red,!!green,!!blue,!!alpha];
            }
            window.__gaiusWebGL.colorMask(red,green,blue,alpha);
            """)
    public static native void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    @JSBody(params = {"index", "red", "green", "blue", "alpha"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,drawBuffer=index|0;
            const bits=(red?1:0)|(green?2:0)|(blue?4:0)|(alpha?8:0);
            if (drawBuffer===0 && state) {
              state.colorMaskBits=bits;
              state.colorMask=[!!red,!!green,!!blue,!!alpha];
            }
            const defaultFramebuffer=!!(state && (state.framebufferBindings.draw|0)===0);
            if (drawBuffer===0 && defaultFramebuffer) {
              gl.colorMask(red,green,blue,alpha);
              return;
            }
            let extension=state ? state.drawBuffersIndexedExtension : undefined;
            if (extension===undefined) {
              extension=gl.getExtension('OES_draw_buffers_indexed') || null;
              if (state) state.drawBuffersIndexedExtension=extension;
            }
            if (extension && typeof extension.colorMaskiOES==='function') {
              extension.colorMaskiOES(drawBuffer,red,green,blue,alpha);
              return;
            }
            if (drawBuffer===0) {
              gl.colorMask(red,green,blue,alpha);
              return;
            }
            const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            stats.indexedColorMaskUnsupported=(stats.indexedColorMaskUnsupported||0)+1;
            """)
    public static native void colorMaski(
            int index, boolean red, boolean green, boolean blue, boolean alpha);

    @JSBody(params = {"func"}, script = """
            const state=window.__gaiusGL,next=func|0;
            if (state && (state.depthFuncValue|0)===next) return;
            if (state) state.depthFuncValue=next;
            window.__gaiusWebGL.depthFunc(func);
            """)
    public static native void depthFunc(int function);

    @JSBody(params = {"enabled"}, script = """
            const state=window.__gaiusGL,next=!!enabled;
            if (state && state.depthMaskValue===next) return;
            if (state) state.depthMaskValue=next;
            window.__gaiusWebGL.depthMask(enabled);
            """)
    public static native void depthMask(boolean enabled);

    @JSBody(params = {"mode", "first", "count"}, script = """
            window.__gaiusGL.executeDraw(0,mode,first,count,0,0,0);
            """)
    public static native void drawArrays(int mode, int first, int count);

    public static void multiDrawArrays(int mode, long firsts, long counts, int drawCount) {
        if (drawCount < 0 || firsts == 0L || counts == 0L) {
            throw new IllegalArgumentException("Invalid WebGL multi-draw arrays arguments");
        }
        for (int index = 0; index < drawCount; index++) {
            drawArrays(
                    mode,
                    MemoryUtil.memGetInt(firsts + (long) index * Integer.BYTES),
                    MemoryUtil.memGetInt(counts + (long) index * Integer.BYTES));
        }
        noteMultiDrawFallbackJs(drawCount, false);
    }

    public static void drawBuffers(IntBuffer buffers) {
        drawBuffersJs(ints(buffers));
    }

    public static void drawBuffers(int[] buffers) {
        drawBuffersJs(Int32Array.fromJavaArray(buffers));
    }

    public static void drawBuffers(int buffer) {
        int[] values = UNIFORM_SCRATCH.get().ints(1);
        values[0] = buffer;
        drawBuffersJs(Int32Array.fromJavaArray(values));
    }

    public static void drawBuffer(int buffer) {
        drawBuffers(buffer);
    }

    public static void drawBuffers(int count, long address) {
        drawBuffersJs(pointerInts(address, count));
    }

    @JSBody(params = {"buffers"}, script = """
            const state=window.__gaiusGL;
            if (state) {
              state.lastDrawBuffers=Array.from(buffers || []);
              state.drawBuffersCalls=(state.drawBuffersCalls||0)+1;
            }
            window.__gaiusWebGL.drawBuffers(buffers);
            """)
    private static native void drawBuffersJs(Int32Array buffers);

    public static void readPixels(
            int x, int y, int width, int height, int format, int type, long pixels) {
        if (boundBufferForTargetJs(PIXEL_PACK_BUFFER) != 0) {
            if (pixels < 0 || pixels > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "WebGL pixel pack buffer offset is out of range: " + pixels);
            }
            readPixelsOffsetJs(x, y, width, height, format, type, (int) pixels);
            return;
        }
        readPixelsBytesJs(
                x,
                y,
                width,
                height,
                format,
                type,
                pointerBytes(pixels, pixelReadLength(width, height, format, type)));
    }

    @JSBody(
            params = {"x", "y", "width", "height", "format", "type", "offset"},
            script = """
                    const gl=window.__gaiusWebGL;
                    gl.readPixels(x,y,width,height,format,type,offset|0);
                    const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.readPixelsCalls=(stats.readPixelsCalls||0)+1;
                    stats.readPixelsPboCalls=(stats.readPixelsPboCalls||0)+1;
                    """)
    private static native void readPixelsOffsetJs(
            int x, int y, int width, int height, int format, int type, int offset);

    @JSBody(
            params = {"x", "y", "width", "height", "format", "type", "pixels"},
            script = """
                    const gl=window.__gaiusWebGL;
                    let view=pixels;
                    if (pixels!==null && pixels!==undefined) {
                      const buffer=pixels.buffer;
                      const offset=pixels.byteOffset||0;
                      const length=pixels.byteLength||0;
                      switch (type|0) {
                        case 0x1400:
                          view=new Int8Array(buffer,offset,length);
                          break;
                        case 0x1401:
                          view=new Uint8Array(buffer,offset,length);
                          break;
                        case 0x1402:
                          view=new Int16Array(buffer,offset,length>>>1);
                          break;
                        case 0x1403:
                        case 0x140B:
                        case 0x8D61:
                          view=new Uint16Array(buffer,offset,length>>>1);
                          break;
                        case 0x1404:
                          view=new Int32Array(buffer,offset,length>>>2);
                          break;
                        case 0x1405:
                        case 0x8033:
                        case 0x8034:
                        case 0x8035:
                        case 0x8367:
                        case 0x84FA:
                          view=new Uint32Array(buffer,offset,length>>>2);
                          break;
                        case 0x1406:
                          view=new Float32Array(buffer,offset,length>>>2);
                          break;
                        default:
                          view=new Uint8Array(buffer,offset,length);
                          break;
                      }
                    }
                    gl.readPixels(x,y,width,height,format,type,view);
                    const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.readPixelsCalls=(stats.readPixelsCalls||0)+1;
                    stats.readPixelsClientCalls=(stats.readPixelsClientCalls||0)+1;
                    """)
    private static native void readPixelsBytesJs(
            int x, int y, int width, int height, int format, int type, Int8Array pixels);

    @JSBody(params = {"mode", "count", "type", "offset"}, script = """
            window.__gaiusGL.executeDraw(1,mode,count,type,offset,0,0);
            """)
    private static native void drawElementsJs(int mode, int count, int type, int offset);

    public static void drawElements(int mode, int count, int type, long offset) {
        drawElementsJs(mode, count, type, (int) offset);
    }

    public static void multiDrawElementsBaseVertex(
            int mode,
            long counts,
            int type,
            long offsets,
            int drawCount,
            long baseVertices) {
        if (drawCount < 0 || counts == 0L || offsets == 0L || baseVertices == 0L) {
            throw new IllegalArgumentException("Invalid WebGL indexed multi-draw arguments");
        }
        for (int index = 0; index < drawCount; index++) {
            drawElementsBaseVertex(
                    mode,
                    MemoryUtil.memGetInt(counts + (long) index * Integer.BYTES),
                    type,
                    MemoryUtil.memGetAddress(
                            offsets + (long) index * org.lwjgl.system.Pointer.POINTER_SIZE),
                    MemoryUtil.memGetInt(baseVertices + (long) index * Integer.BYTES));
        }
        noteMultiDrawFallbackJs(drawCount, true);
    }

    @JSBody(params = {"drawCount", "indexed"}, script = """
            const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            stats.multiDrawFallbackCalls=(stats.multiDrawFallbackCalls||0)+1;
            stats.multiDrawFallbackDraws=(stats.multiDrawFallbackDraws||0)+(drawCount|0);
            if (indexed) {
              stats.multiDrawIndexedFallbackCalls=(stats.multiDrawIndexedFallbackCalls||0)+1;
            }
            """)
    private static native void noteMultiDrawFallbackJs(int drawCount, boolean indexed);

    @JSBody(script = """
            if (window.__gaiusReadWebGLErrors===undefined) {
              try {
                window.__gaiusReadWebGLErrors=new URLSearchParams(location.search).get('glErrors')==='1';
              } catch (ignored) {
                window.__gaiusReadWebGLErrors=false;
              }
            }
            return window.__gaiusReadWebGLErrors ? (window.__gaiusWebGL.getError()|0) : 0;
            """)
    public static native int getError();

    @JSBody(params = {"parameter"}, script = """
            const state=window.__gaiusGL;
            if (state && parameter===0x8CA6) return state.framebufferBindings.draw|0;
            if (state && parameter===0x8CAA) return state.framebufferBindings.read|0;
            const value=window.__gaiusWebGL.getParameter(parameter);
            return typeof value==='number' ? value|0 : 0;
            """)
    public static native int getInteger(int parameter);

    @JSBody(params = {"parameter"}, script = """
            const value=window.__gaiusWebGL.getParameter(parameter);
            return typeof value==='number' ? +value : 0;
            """)
    public static native float getFloat(int parameter);

    @JSBody(params = {"parameter"}, script = """
            const gl=window.__gaiusWebGL;
            if (parameter===0x1F00) return gl.getParameter(gl.VENDOR)||'Browser';
            if (parameter===0x1F01) return gl.getParameter(gl.RENDERER)||'WebGL2';
            if (parameter===0x1F02) return 'OpenGL 3.3 (WebGL2)';
            if (parameter===0x8B8C) return gl.getParameter(gl.SHADING_LANGUAGE_VERSION)||'WebGL GLSL ES 3.00';
            return '';
            """)
    public static native String getString(int parameter);

    @JSBody(params = {"operation"}, script = """
            const gl=window.__gaiusWebGL;
            if (gl.logicOp) gl.logicOp(operation);
            """)
    public static native void logicOp(int operation);

    public static void pixelStorei(int parameter, int value) {
        int browserValue = value;
        boolean unchanged = false;
        switch (parameter) {
            case 0x0D02 -> {
                browserValue = Math.max(0, value);
                unchanged = packRowLength == browserValue;
                packRowLength = browserValue;
            }
            case 0x0D03 -> {
                browserValue = Math.max(0, value);
                unchanged = packSkipRows == browserValue;
                packSkipRows = browserValue;
            }
            case 0x0D04 -> {
                browserValue = Math.max(0, value);
                unchanged = packSkipPixels == browserValue;
                packSkipPixels = browserValue;
            }
            case 0x0D05 -> {
                browserValue = webGlPixelAlignment(value);
                unchanged = packAlignment == browserValue;
                packAlignment = browserValue;
            }
            case 0x0CF2 -> {
                browserValue = Math.max(0, value);
                unchanged = unpackRowLength == browserValue;
                unpackRowLength = browserValue;
            }
            case 0x0CF3 -> {
                browserValue = Math.max(0, value);
                unchanged = unpackSkipRows == browserValue;
                unpackSkipRows = browserValue;
            }
            case 0x0CF4 -> {
                browserValue = Math.max(0, value);
                unchanged = unpackSkipPixels == browserValue;
                unpackSkipPixels = browserValue;
            }
            case 0x0CF5 -> {
                browserValue = webGlPixelAlignment(value);
                unchanged = unpackAlignment == browserValue;
                unpackAlignment = browserValue;
            }
            default -> {
            }
        }
        if (!unchanged) {
            pixelStoreiJs(parameter, browserValue);
        }
    }

    @JSBody(params = {"parameter", "value"}, script = """
            const state=window.__gaiusGL;
            if (state) {
              switch (parameter|0) {
                case 0x0D02: state.packRowLength=value|0; break;
                case 0x0D03: state.packSkipRows=value|0; break;
                case 0x0D04: state.packSkipPixels=value|0; break;
                case 0x0D05: state.packAlignment=value|0; break;
                case 0x0CF2: state.unpackRowLength=value|0; break;
                case 0x0CF3: state.unpackSkipRows=value|0; break;
                case 0x0CF4: state.unpackSkipPixels=value|0; break;
                case 0x0CF5: state.unpackAlignment=value|0; break;
                default: break;
              }
            }
            window.__gaiusWebGL.pixelStorei(parameter,value);
            """)
    private static native void pixelStoreiJs(int parameter, int value);

    public static void polygonMode(int face, int mode) {
        // WebGL only supports filled polygons.
    }

    @JSBody(params = {"factor", "units"}, script = """
            const state=window.__gaiusGL;
            if (state && state.polygonOffsetFactor===factor && state.polygonOffsetUnits===units) return;
            if (state) { state.polygonOffsetFactor=factor; state.polygonOffsetUnits=units; }
            window.__gaiusWebGL.polygonOffset(factor,units);
            """)
    public static native void polygonOffset(float factor, float units);

    @JSBody(params = {"x", "y", "width", "height"},
            script = """
                    const state=window.__gaiusGL;
                    if (state && state.viewportX===(x|0) && state.viewportY===(y|0)
                        && state.viewportWidth===(width|0) && state.viewportHeight===(height|0)) return;
                    if (state) {
                      state.viewportX=x|0; state.viewportY=y|0;
                      state.viewportWidth=width|0; state.viewportHeight=height|0;
                    }
                    window.__gaiusWebGL.viewport(x,y,width,height);
                    """)
    public static native void viewport(int x, int y, int width, int height);

    @JSBody(params = {"x", "y", "width", "height"},
            script = """
                    const state=window.__gaiusGL;
                    if (state && state.scissorX===(x|0) && state.scissorY===(y|0)
                        && state.scissorWidth===(width|0) && state.scissorHeight===(height|0)) return;
                    if (state) {
                      state.scissorX=x|0; state.scissorY=y|0;
                      state.scissorWidth=width|0; state.scissorHeight=height|0;
                    }
                    window.__gaiusWebGL.scissor(x,y,width,height);
                    """)
    public static native void scissor(int x, int y, int width, int height);

    @JSBody(params = {"sourceRgb", "destinationRgb", "sourceAlpha", "destinationAlpha"}, script = """
            const state=window.__gaiusGL;
            if (state && state.blendSourceRgb!==undefined
                && (state.blendSourceRgb|0)===(sourceRgb|0)
                && (state.blendDestinationRgb|0)===(destinationRgb|0)
                && (state.blendSourceAlpha|0)===(sourceAlpha|0)
                && (state.blendDestinationAlpha|0)===(destinationAlpha|0)) return;
            if (state) {
              state.blendSourceRgb=sourceRgb|0; state.blendDestinationRgb=destinationRgb|0;
              state.blendSourceAlpha=sourceAlpha|0; state.blendDestinationAlpha=destinationAlpha|0;
            }
            window.__gaiusWebGL.blendFuncSeparate(sourceRgb,destinationRgb,sourceAlpha,destinationAlpha);
            """)
    public static native void blendFuncSeparate(
            int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);

    @JSBody(params = {"source", "destination"}, script = """
            const state=window.__gaiusGL;
            if (state && state.blendSourceRgb!==undefined
                && (state.blendSourceRgb|0)===(source|0)
                && (state.blendDestinationRgb|0)===(destination|0)
                && (state.blendSourceAlpha|0)===(source|0)
                && (state.blendDestinationAlpha|0)===(destination|0)) return;
            if (state) {
              state.blendSourceRgb=source|0; state.blendDestinationRgb=destination|0;
              state.blendSourceAlpha=source|0; state.blendDestinationAlpha=destination|0;
            }
            window.__gaiusWebGL.blendFunc(source,destination);
            """)
    public static native void blendFunc(int source, int destination);

    @JSBody(params = {"mode"}, script = """
            const state=window.__gaiusGL,next=mode|0;
            if (state && (state.blendEquationRgb|0)===next
                && (state.blendEquationAlpha|0)===next) return;
            if (state) { state.blendEquationRgb=next; state.blendEquationAlpha=next; }
            window.__gaiusWebGL.blendEquation(mode);
            """)
    public static native void blendEquation(int mode);

    @JSBody(params = {"modeRgb", "modeAlpha"}, script = """
            const state=window.__gaiusGL;
            if (state && (state.blendEquationRgb|0)===(modeRgb|0)
                && (state.blendEquationAlpha|0)===(modeAlpha|0)) return;
            if (state) { state.blendEquationRgb=modeRgb|0; state.blendEquationAlpha=modeAlpha|0; }
            window.__gaiusWebGL.blendEquationSeparate(modeRgb,modeAlpha);
            """)
    public static native void blendEquationSeparate(int modeRgb, int modeAlpha);

    @JSBody(params = {"unit"}, script = """
            const state=window.__gaiusGL;
            const next=(unit-0x84C0)|0;
            if (state && (state.activeTextureUnit|0)===next) return;
            if (state) state.activeTextureUnit=next;
            window.__gaiusWebGL.activeTexture(unit);
            """)
    public static native void activeTexture(int unit);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.textures.set(id,window.__gaiusWebGL.createTexture()); return id|0;
            """)
    public static native int genTexture();

    @JSBody(params = {"texture"}, script = """
            const state=window.__gaiusGL, object=state.textures.get(texture);
            if (object) window.__gaiusWebGL.deleteTexture(object);
            state.invalidateGuiItemAtlasBlitCache(texture,'delete');
            state.textureInfo.delete(texture);
            if (state.textureBufferDefaults) state.textureBufferDefaults.delete(texture|0);
            if (state.textureParameters) state.textureParameters.delete(texture|0);
            state.textureBufferInfo.delete(texture|0);
            const stats=window.__gaiusGLStats;
            if (stats && stats.textureInfo) delete stats.textureInfo[String(texture|0)];
            const staleBindings=[];
            state.textureBindings.forEach(function(bound,key) {
              if ((bound|0)===(texture|0)) staleBindings.push(key);
            });
            for (let i=0;i<staleBindings.length;i++) state.textureBindings.delete(staleBindings[i]);
            state.framebufferColorTextures.forEach(function(mapped, framebuffer) {
              if ((mapped|0)===(texture|0)) {
                state.framebufferColorTextures.delete(framebuffer|0);
                if (state.framebufferColorTextureMisses) {
                  state.framebufferColorTextureMisses.add(framebuffer|0);
                }
                state.refreshFramebufferOffscreen512(framebuffer|0);
              }
            });
            state.textures.delete(texture);
            """)
    public static native void deleteTexture(int texture);

    @JSBody(params = {"target", "texture"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const webTarget=target===0x8C2A ? gl.TEXTURE_2D : target;
            const unit=state.activeTextureUnit || 0;
            const keyBase=unit*65536;
            const targetKey=keyBase+((target|0)&65535);
            const webKey=keyBase+((webTarget|0)&65535);
            const alreadyBound=state.textureBindings.has(webKey)
              && (state.textureBindings.get(webKey)|0)===(texture|0);
            const defaultsCandidate=target===0x8C2A && texture!==0
              && !state.textureBufferDefaults.has(texture|0);
            const object=(!alreadyBound || defaultsCandidate)
              ? (texture===0?null:state.textures.get(texture)) : null;
            const needsBufferDefaults=defaultsCandidate && !!object;
            if (!state.textureBindings.has(targetKey)
                || (state.textureBindings.get(targetKey)|0)!==(texture|0)) {
              state.textureBindings.set(targetKey,texture|0);
            }
            if (!alreadyBound) state.textureBindings.set(webKey,texture|0);
            const aliasKey=keyBase+35882;
            if (webTarget===gl.TEXTURE_2D && (!state.textureBindings.has(aliasKey)
                || (state.textureBindings.get(aliasKey)|0)!==(texture|0))) {
              state.textureBindings.set(aliasKey,texture|0);
            }
            if (!alreadyBound) gl.bindTexture(webTarget,object);
            if (target===0x8C2A) {
              if (needsBufferDefaults) {
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
                let parameters=state.textureParameters.get(texture|0);
                if (!parameters) {
                  parameters=new Map();
                  state.textureParameters.set(texture|0,parameters);
                }
                parameters.set(gl.TEXTURE_MIN_FILTER,gl.NEAREST);
                parameters.set(gl.TEXTURE_MAG_FILTER,gl.NEAREST);
                parameters.set(gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
                parameters.set(gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
                state.textureBufferDefaults.add(texture|0);
              }
            }
            """)
    public static native void bindTexture(int target, int texture);

    @JSBody(params = {"target", "parameter", "value"},
            script = """
                    const gl=window.__gaiusWebGL,state=window.__gaiusGL;
                    const texture=state.boundTextureId(target)|0;
                    if (texture) {
                      let parameters=state.textureParameters.get(texture);
                      if (!parameters) {
                        parameters=new Map();
                        state.textureParameters.set(texture,parameters);
                      } else if (parameters.has(parameter)
                          && (parameters.get(parameter)|0)===(value|0)) {
                        return;
                      }
                      parameters.set(parameter,value|0);
                    }
                    gl.texParameteri(target===0x8C2A ? gl.TEXTURE_2D : target,parameter,value);
                    """)
    public static native void texParameteri(int target, int parameter, int value);

    public static void texImage2D(
            int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, ByteBuffer pixels) {
        texImage2DJs(target, level, internalFormat, width, height, border, format, type, bytes(pixels));
    }

    @JSBody(params = {
            "target", "level", "internalFormat", "width", "height",
            "border", "format", "type", "pixels"
    }, script = """
            if (target === 0x8064) {
              return;
            }
            if (internalFormat === 0x81A7 && format === 0x1902 && type === 0x1406) {
              internalFormat = 0x8CAC;
            }
            if (internalFormat === 0x8231) {
              format = 0x8D94;
              if (type === 0x1401) type = 0x1400;
            }
            if (pixels !== null && pixels !== undefined) {
              if (type !== 0x1400) {
                pixels = new Uint8Array(
                  pixels.buffer,pixels.byteOffset || 0,pixels.byteLength);
              } else {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.textureUploadSignedViewSkips=(stats.textureUploadSignedViewSkips||0)+1;
              }
            }
            const gl=window.__gaiusWebGL;
            try {
              gl.texImage2D(target,level,internalFormat,width,height,border,format,type,pixels);
              if (window.__gaiusGL && window.__gaiusGL.recordTextureUpload) {
                window.__gaiusGL.recordTextureUpload(
                  'texImage2D',target,level,0,0,width,height,internalFormat,format,type,pixels);
              }
            } catch (error) {
              if (window.__gaiusGL && window.__gaiusGL.recordTextureError) {
                window.__gaiusGL.recordTextureError(
                  'texImage2D',target,level,width,height,format,type,pixels,error);
              }
              throw error;
            }
            """)
    private static native void texImage2DJs(
            int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, Int8Array pixels);

    public static void texSubImage2D(
            int target, int level, int x, int y, int width, int height,
            int format, int type, ByteBuffer pixels) {
        texSubImage2DJs(target, level, x, y, width, height, format, type, bytes(pixels));
    }

    public static void texSubImage2D(
            int target, int level, int x, int y, int width, int height,
            int format, int type, long pixels) {
        if (boundBufferForTargetJs(PIXEL_UNPACK_BUFFER) != 0) {
            if (pixels < 0 || pixels > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("WebGL pixel unpack buffer offset is out of range: " + pixels);
            }
            texSubImage2DOffsetJs(
                    target, level, x, y, width, height, format, type, (int) pixels);
            return;
        }
        texSubImage2DJs(target, level, x, y, width, height, format, type,
                pointerBytes(pixels, textureUploadLength(width, height, format, type)));
    }

    @JSBody(params = {
            "target", "level", "x", "y", "width", "height", "format", "type", "offset"
    }, script = """
            if (format === 0x1903 && type === 0x1400) {
              format = 0x8D94;
            }
            const gl=window.__gaiusWebGL;
            try {
              gl.texSubImage2D(target,level,x,y,width,height,format,type,offset);
              if (window.__gaiusGL && window.__gaiusGL.recordTextureUpload) {
                window.__gaiusGL.recordTextureUpload(
                  'texSubImage2D-pbo',target,level,x,y,width,height,0,format,type,offset);
              }
            } catch (error) {
              if (window.__gaiusGL && window.__gaiusGL.recordTextureError) {
                window.__gaiusGL.recordTextureError(
                  'texSubImage2D-pbo',target,level,width,height,format,type,offset,error);
              }
              throw error;
            }
            """)
    private static native void texSubImage2DOffsetJs(
            int target, int level, int x, int y, int width, int height,
            int format, int type, int offset);

    @JSBody(params = {
            "target", "level", "x", "y", "width", "height", "format", "type", "pixels"
    }, script = """
            if (format === 0x1903 && type === 0x1400) {
              format = 0x8D94;
            }
            if (pixels !== null && pixels !== undefined) {
              if (type !== 0x1400) {
                pixels = new Uint8Array(
                  pixels.buffer,pixels.byteOffset || 0,pixels.byteLength);
              } else {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.textureUploadSignedViewSkips=(stats.textureUploadSignedViewSkips||0)+1;
              }
            }
            const gl=window.__gaiusWebGL;
            try {
              gl.texSubImage2D(target,level,x,y,width,height,format,type,pixels);
              if (window.__gaiusGL && window.__gaiusGL.recordTextureUpload) {
                window.__gaiusGL.recordTextureUpload(
                  'texSubImage2D',target,level,x,y,width,height,0,format,type,pixels);
              }
            } catch (error) {
              if (window.__gaiusGL && window.__gaiusGL.recordTextureError) {
                window.__gaiusGL.recordTextureError(
                  'texSubImage2D',target,level,width,height,format,type,pixels,error);
              }
              throw error;
            }
            """)
    private static native void texSubImage2DJs(
            int target, int level, int x, int y, int width, int height,
            int format, int type, Int8Array pixels);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.buffers.set(id,window.__gaiusWebGL.createBuffer()); return id|0;
            """)
    public static native int genBuffer();

    public static int createBuffer() {
        return genBuffer();
    }

    /**
     * Returns a browser-local query id. WebGL query objects are deliberately
     * not created: Minecraft uses these calls only for optional timing paths,
     * and a synthetic id keeps every LWJGL overload off the native ICD path.
     */
    public static int genQuery() {
        int id = nextSyntheticQuery++;
        if (id <= 0) {
            nextSyntheticQuery = 2;
            return 1;
        }
        return id;
    }

    public static void genQueries(IntBuffer queries) {
        if (queries == null) {
            return;
        }
        int position = queries.position();
        for (int index = 0; index < queries.remaining(); index++) {
            queries.put(position + index, genQuery());
        }
    }

    public static void genQueries(int[] queries) {
        if (queries == null) {
            return;
        }
        for (int index = 0; index < queries.length; index++) {
            queries[index] = genQuery();
        }
    }

    public static void genQueries(int count, long address) {
        if (count <= 0 || address == 0L) {
            return;
        }
        for (int index = 0; index < count; index++) {
            MemoryUtil.memPutInt(address + (long) index * Integer.BYTES, genQuery());
        }
    }

    public static void deleteBuffer(int buffer) {
        releaseMappedBuffer(buffer);
        deleteBufferJs(buffer);
    }

    @JSBody(params = {"buffer"}, script = """
            const state=window.__gaiusGL, object=state.buffers.get(buffer);
            state.forgetPhysicalElementBuffer(object);
            if (object) window.__gaiusWebGL.deleteBuffer(object); state.buffers.delete(buffer);
            state.bufferSizes.delete(buffer);
            state.deleteBufferShadow(buffer);
            if (state.shadowRequiredBuffers) state.shadowRequiredBuffers.delete(buffer|0);
            if (state.misalignedBufferRefs) state.misalignedBufferRefs.delete(buffer|0);
            state.dropBufferDerivedCaches(buffer|0);
            state.bufferVersions.delete(buffer);
            state.textureBufferInfo.forEach(function(info,texture) {
              if (info && (info.buffer|0)===(buffer|0)) state.textureBufferInfo.delete(texture|0);
            });
            state.boundBuffers.forEach(function(bound,target) {
              if ((bound|0)===(buffer|0)) state.boundBuffers.set(target,0);
            });
            if (state.indexedBufferBindings) {
              state.indexedBufferBindings.forEach(function(binding,key) {
                if (binding && (binding.buffer|0)===(buffer|0)) state.indexedBufferBindings.delete(key);
              });
            }
            const referencedVaoIds=state.vaoBufferRefs
              ? Array.from(state.vaoBufferRefs.get(buffer|0)||[]) : [];
            for(let vaoIndex=0;vaoIndex<referencedVaoIds.length;vaoIndex++) {
              const vao=state.vaoEmu.get(referencedVaoIds[vaoIndex]|0);
              if(!vao)continue;
              let changed=false;
              if ((vao.elementArrayBuffer|0)===(buffer|0)) {
                vao.elementArrayBuffer=0;
                vao.elementArrayBufferObject=null;
                changed=true;
              }
              vao.attribPointers.forEach(function(pointer, attrib) {
                if ((pointer.buffer|0)===(buffer|0)) {
                  state.setAttribBufferPresence(vao,attrib|0,false);
                  state.setAttribMisaligned(vao,attrib|0,false);
                  vao.attribPointers.delete(attrib|0);
                  changed=true;
                }
              });
              vao.vertexBuffers.forEach(function(vertexBuffer, binding) {
                if ((vertexBuffer.buffer|0)===(buffer|0)) {
                  vao.vertexBuffers.delete(binding|0);
                  changed=true;
                }
              });
              if(vao.bufferRefCounts)vao.bufferRefCounts.delete(buffer|0);
              if(changed)state.bumpVaoAttribVersion(vao);
            }
            if(state.vaoBufferRefs)state.vaoBufferRefs.delete(buffer|0);
            """)
    private static native void deleteBufferJs(int buffer);

    @JSBody(params = {"target", "buffer"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,current=state.boundBuffers.get(target)|0;
            if (target===gl.ELEMENT_ARRAY_BUFFER) {
              const vao=state.getVaoEmu();
              const nextId=buffer|0;
              if ((vao.elementArrayBuffer|0)===nextId) {
                state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null);
                if (current!==nextId) state.boundBuffers.set(target,nextId);
                return;
              }
              const object=nextId===0?null:state.buffers.get(nextId);
              state.bindPhysicalElementBuffer(vao,object || null);
              state.replaceVaoBufferRef(vao,vao.elementArrayBuffer|0,nextId);
              vao.elementArrayBuffer=nextId;
              vao.elementArrayBufferObject=object || null;
              if (current!==nextId) state.boundBuffers.set(target,nextId);
              return;
            }
            if (target!==gl.ELEMENT_ARRAY_BUFFER && current===(buffer|0)) {
              return;
            }
            if (buffer && (target===0x8C2A || target===gl.COPY_READ_BUFFER || target===gl.COPY_WRITE_BUFFER)) {
              state.markBufferShadowRequired(buffer,'target:'+target);
            }
            gl.bindBuffer(target,buffer===0?null:state.buffers.get(buffer));
            state.boundBuffers.set(target,buffer);
            """)
    public static native void bindBuffer(int target, int buffer);

    @JSBody(params = {"target", "size", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (target===gl.ELEMENT_ARRAY_BUFFER) {
              state.ensureLogicalElementBuffer(state.getVaoEmu());
            }
            const buffer=state.boundBuffers.get(target)|0;
            const requested=Number(size);
            const actual=target===0x8A11 ? Math.max(requested,256) : requested;
            gl.bufferData(target,actual,usage);
            state.noteBufferUpload(0,actual,false);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              state.shadowBufferDataForTarget(target,buffer,null,actual);
            }
            """)
    private static native void bufferDataSizeJs(int target, int size, int usage);

    public static void bufferData(int target, long size, int usage) {
        bufferDataSizeJs(target, (int) size, usage);
    }

    public static void bufferData(int target, ByteBuffer data, int usage) {
        bufferDataJs(target, bytes(data), usage);
    }

    @JSBody(params = {"target", "data", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (target===gl.ELEMENT_ARRAY_BUFFER) {
              state.ensureLogicalElementBuffer(state.getVaoEmu());
            }
            const buffer=state.boundBuffers.get(target)|0;
            const sourceBytes=data ? data.byteLength : 0;
            const upload=state.prepareBufferDataUpload(data,target===0x8A11);
            const actual=upload ? upload.byteLength : 0;
            gl.bufferData(target,upload,usage);
            state.noteBufferUpload(sourceBytes,actual,false);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              state.shadowBufferDataForTarget(target,buffer,upload,actual);
            }
            """)
    private static native void bufferDataJs(int target, Int8Array data, int usage);

    public static void bufferSubData(int target, long offset, ByteBuffer data) {
        bufferSubDataJs(target, (int) offset, bytes(data));
    }

    @JSBody(params = {"target", "offset", "data"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (target===gl.ELEMENT_ARRAY_BUFFER) {
              state.ensureLogicalElementBuffer(state.getVaoEmu());
            }
            gl.bufferSubData(target,Number(offset),data);
            state.noteBufferUpload(data ? data.byteLength : 0,data ? data.byteLength : 0,true);
            const buffer=state.boundBuffers.get(target)|0;
            if (buffer && data) {
              const end=Number(offset)+data.byteLength;
              const known=state.bufferSizes.get(buffer)||0;
              if (end > known) state.bufferSizes.set(buffer,end);
              state.shadowBufferSubDataForTarget(target,buffer,Number(offset),data);
            }
            """)
    private static native void bufferSubDataJs(int target, int offset, Int8Array data);

    public static void namedBufferData(int buffer, long size, int usage) {
        namedBufferDataSizeJs(buffer, (int) size, usage);
    }

    @JSBody(params = {"buffer", "size", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousId=state.boundBuffers.get(gl.COPY_WRITE_BUFFER)|0;
            const previous=previousId ? state.buffers.get(previousId) : null;
            const requested=Number(size);
            const actual=Math.max(requested,256);
            const bindingMatches=previousId===(buffer|0);
            if (!bindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferData(gl.COPY_WRITE_BUFFER,actual,usage);
            state.noteBufferUpload(0,actual,false);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              if (state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(buffer|0)) {
                state.shadowBufferData(buffer,null,actual);
              } else {
                if (!state.dropBufferShadow(buffer,'named-buffer')) state.bumpBufferVersion(buffer);
              }
            }
            if (!bindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            state.noteNamedBufferBindings(bindingMatches?0:2,bindingMatches?2:0);
            """)
    private static native void namedBufferDataSizeJs(int buffer, int size, int usage);

    public static void namedBufferData(int buffer, ByteBuffer data, int usage) {
        namedBufferDataJs(buffer, bytes(data), usage);
    }

    @JSBody(params = {"buffer", "data", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousId=state.boundBuffers.get(gl.COPY_WRITE_BUFFER)|0;
            const previous=previousId ? state.buffers.get(previousId) : null;
            const sourceBytes=data ? data.byteLength : 0;
            const upload=state.prepareBufferDataUpload(data,true);
            const actual=upload ? upload.byteLength : 0;
            const bindingMatches=previousId===(buffer|0);
            if (!bindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferData(gl.COPY_WRITE_BUFFER,upload,usage);
            state.noteBufferUpload(sourceBytes,actual,false);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              if (state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(buffer|0)) {
                state.shadowBufferData(buffer,upload,actual);
              } else {
                if (!state.dropBufferShadow(buffer,'named-buffer')) state.bumpBufferVersion(buffer);
              }
            }
            if (!bindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            state.noteNamedBufferBindings(bindingMatches?0:2,bindingMatches?2:0);
            """)
    private static native void namedBufferDataJs(int buffer, Int8Array data, int usage);

    public static void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        namedBufferSubDataJs(buffer, (int) offset, bytes(data));
    }

    @JSBody(params = {"buffer", "offset", "data"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousId=state.boundBuffers.get(gl.COPY_WRITE_BUFFER)|0;
            const previous=previousId ? state.buffers.get(previousId) : null;
            const bindingMatches=previousId===(buffer|0);
            if (!bindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferSubData(gl.COPY_WRITE_BUFFER,Number(offset),data);
            state.noteBufferUpload(data ? data.byteLength : 0,data ? data.byteLength : 0,true);
            if (buffer && data) {
              const end=Number(offset)+data.byteLength;
              const known=state.bufferSizes.get(buffer)||0;
              if (end > known) state.bufferSizes.set(buffer,end);
              if (state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(buffer|0)) {
                state.shadowBufferSubData(buffer,Number(offset),data);
              } else {
                if (!state.dropBufferShadow(buffer,'named-buffer')) state.bumpBufferVersion(buffer);
              }
            }
            if (!bindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            state.noteNamedBufferBindings(bindingMatches?0:2,bindingMatches?2:0);
            """)
    private static native void namedBufferSubDataJs(int buffer, int offset, Int8Array data);

    public static ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        checkMappedRange(offset, length);
        int logicalBuffer = boundBufferForTargetJs(target);
        if (logicalBuffer == 0) {
            throw new IllegalStateException("Cannot map an unbound WebGL buffer target: " + target);
        }
        if (MAPPED_BUFFERS.containsKey(target)) {
            throw new IllegalStateException("WebGL buffer target is already mapped: " + target);
        }
        ensureBufferNotMapped(logicalBuffer);
        ByteBuffer buffer = MemoryUtil.memAlloc((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(target, new MappedBuffer(logicalBuffer, offset, access, buffer));
        noteMappedBufferCountJs(MAPPED_BUFFERS.size());
        return buffer;
    }

    public static long mapBufferRangeAddress(int target, long offset, long length, int access) {
        return MemoryUtil.memAddress(mapBufferRange(target, offset, length, access));
    }

    public static boolean unmapBuffer(int target) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(target);
        if (mapped == null) {
            return true;
        }
        try {
            if (mapped.uploadOnUnmap()) {
                bufferSubDataJs(target, (int) mapped.offset, allBytes(mapped.buffer));
            }
        } finally {
            MemoryUtil.memFree(mapped.buffer);
            noteMappedBufferCountJs(MAPPED_BUFFERS.size());
        }
        return true;
    }

    public static void flushMappedBufferRange(int target, long offset, long length) {
        MappedBuffer mapped = MAPPED_BUFFERS.get(target);
        if (mapped == null || length <= 0L) {
            return;
        }
        int absoluteOffset = absoluteMappedOffset(mapped, offset, length);
        bufferSubDataJs(target, absoluteOffset, bytesSlice(mapped.buffer, offset, length));
    }

    public static ByteBuffer mapNamedBufferRange(int buffer, long offset, long length, int access) {
        checkMappedRange(offset, length);
        if (buffer == 0) {
            throw new IllegalStateException("Cannot map WebGL buffer 0");
        }
        ensureBufferNotMapped(buffer);
        ByteBuffer byteBuffer = MemoryUtil.memAlloc((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(
                namedBufferKey(buffer), new MappedBuffer(buffer, offset, access, byteBuffer));
        noteMappedBufferCountJs(MAPPED_BUFFERS.size());
        return byteBuffer;
    }

    public static boolean unmapNamedBuffer(int buffer) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(namedBufferKey(buffer));
        if (mapped == null) {
            return true;
        }
        try {
            if (mapped.uploadOnUnmap()) {
                namedBufferSubDataJs(buffer, (int) mapped.offset, allBytes(mapped.buffer));
            }
        } finally {
            MemoryUtil.memFree(mapped.buffer);
            noteMappedBufferCountJs(MAPPED_BUFFERS.size());
        }
        return true;
    }

    public static void flushMappedNamedBufferRange(int buffer, long offset, long length) {
        MappedBuffer mapped = MAPPED_BUFFERS.get(namedBufferKey(buffer));
        if (mapped == null || length <= 0L) {
            return;
        }
        int absoluteOffset = absoluteMappedOffset(mapped, offset, length);
        namedBufferSubDataJs(buffer, absoluteOffset, bytesSlice(mapped.buffer, offset, length));
    }

    private static int namedBufferKey(int buffer) {
        return 0x40000000 | buffer;
    }

    private static void checkMappedRange(long offset, long length) {
        if (offset < 0L || length < 0L || length > Integer.MAX_VALUE
                || offset > Integer.MAX_VALUE - length) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer range: " + offset + " + " + length);
        }
    }

    private static int absoluteMappedOffset(MappedBuffer mapped, long relativeOffset, long length) {
        if (relativeOffset < 0L || length < 0L
                || relativeOffset > mapped.buffer.capacity() - length) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer flush range: "
                            + relativeOffset + " + " + length);
        }
        long absoluteOffset = mapped.offset + relativeOffset;
        if (absoluteOffset < 0L || absoluteOffset > Integer.MAX_VALUE - length) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer absolute range: "
                            + absoluteOffset + " + " + length);
        }
        return (int) absoluteOffset;
    }

    private static void ensureBufferNotMapped(int buffer) {
        for (MappedBuffer mapped : MAPPED_BUFFERS.values()) {
            if (mapped.logicalBuffer == buffer) {
                throw new IllegalStateException("WebGL buffer is already mapped: " + buffer);
            }
        }
    }

    private static void releaseMappedBuffer(int buffer) {
        List<Integer> staleKeys = new java.util.ArrayList<>();
        for (Map.Entry<Integer, MappedBuffer> entry : MAPPED_BUFFERS.entrySet()) {
            if (entry.getValue().logicalBuffer == buffer) {
                staleKeys.add(entry.getKey());
            }
        }
        for (int key : staleKeys) {
            MappedBuffer mapped = MAPPED_BUFFERS.remove(key);
            if (mapped != null) {
                MemoryUtil.memFree(mapped.buffer);
            }
        }
        noteMappedBufferCountJs(MAPPED_BUFFERS.size());
    }

    /** Releases direct mapped-buffer storage when a lost WebGL context requires a reload. */
    private static void releaseAllMappedBuffers() {
        List<MappedBuffer> staleMappings = new java.util.ArrayList<>(MAPPED_BUFFERS.values());
        MAPPED_BUFFERS.clear();
        for (MappedBuffer mapped : staleMappings) {
            MemoryUtil.memFree(mapped.buffer);
        }
        noteMappedBufferCountJs(0);
    }

    @JSBody(params = {"target"}, script = """
            const state=window.__gaiusGL,gl=window.__gaiusWebGL;
            if (!state) return 0;
            if ((target|0)===(gl.ELEMENT_ARRAY_BUFFER|0)) {
              const vao=state.getVaoEmu();
              return vao ? vao.elementArrayBuffer|0 : 0;
            }
            return state.boundBuffers.get(target|0)|0;
            """)
    private static native int boundBufferForTargetJs(int target);

    @JSBody(params = {"count"}, script = """
            const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            stats.mappedBufferRegions=count|0;
            """)
    private static native void noteMappedBufferCountJs(int count);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.shaders.set(id,window.__gaiusWebGL.createShader(0x8B31)); return id|0;
            """)
    public static native int createVertexShader();

    @JSBody(params = {"type"}, script = """
            const state=window.__gaiusGL, id=state.next++;
            state.shaders.set(id,window.__gaiusWebGL.createShader(type)); return id|0;
            """)
    public static native int createShader(int type);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.programs.set(id,window.__gaiusWebGL.createProgram()); return id|0;
            """)
    public static native int createProgram();

    public static void shaderSource(int shader, CharSequence source) {
        shaderSourceJs(shader, translateShaderSource(source.toString()));
    }

    public static void shaderSourceArray(int shader, CharSequence[] sources) {
        StringBuilder joined = new StringBuilder();
        for (CharSequence source : sources) {
            if (source != null) {
                joined.append(source);
            }
        }
        shaderSourceJs(shader, translateShaderSource(joined.toString()));
    }

    public static void shaderSourceNative(int shader, int count, long strings, long lengths) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < count; i++) {
            long address = MemoryUtil.memGetAddress(strings + (long) i * 8L);
            int length = lengths == 0L ? cStringLength(address) : MemoryUtil.memGetInt(lengths + (long) i * 4L);
            if (address != 0L && length > 0) {
                byte[] bytes = new byte[length];
                for (int j = 0; j < length; j++) {
                    bytes[j] = MemoryUtil.memGetByte(address + j);
                }
                joined.append(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        shaderSourceJs(shader, translateShaderSource(joined.toString()));
    }

    private static int cStringLength(long address) {
        int length = 0;
        while (MemoryUtil.memGetByte(address + length) != 0) {
            length++;
        }
        return length;
    }

    private static String translateShaderSource(String source) {
        String translated = source;
        if (translated.startsWith("#version 330")) {
            int lineEnd = translated.indexOf('\n');
            String rest = lineEnd < 0 ? "" : translated.substring(lineEnd + 1);
            translated = "#version 300 es\nprecision highp float;\nprecision highp int;\n" + rest;
        } else if (translated.startsWith("#version 150")) {
            int lineEnd = translated.indexOf('\n');
            String rest = lineEnd < 0 ? "" : translated.substring(lineEnd + 1);
            translated = "#version 300 es\nprecision highp float;\nprecision highp int;\n" + rest;
        }
        translated = translated
                .replace("uv / 256.0", "vec2(uv) / 256.0")
                .replace("texCoord2 = UV2;", "texCoord2 = vec2(UV2);")
                .replace("floor(texCoord.x * 16) / 15", "floor(texCoord.x * 16.0) / 15.0")
                .replace("floor(texCoord.y * 16) / 15", "floor(texCoord.y * 16.0) / 15.0")
                .replace("Position + (ChunkPosition - CameraBlockPos) + CameraOffset",
                        "Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset")
                .replace("1.0f / TextureSize", "vec2(1.0) / vec2(TextureSize)")
                .replace("1.0 / TextureSize", "vec2(1.0) / vec2(TextureSize)")
                .replace("vec3(cellX, 0, cellZ)", "vec3(float(cellX), 0.0, float(cellZ))")
                .replace("linear_fog_value(vertexDistance, 0, FogCloudsEnd)",
                        "linear_fog_value(vertexDistance, 0.0, FogCloudsEnd)")
                .replace("uniform isamplerBuffer CloudFaces;", "uniform highp isampler2D CloudFaces;")
                .replace("texelFetch(CloudFaces, index).r",
                        "texelFetch(CloudFaces, ivec2(index % 4096, index / 4096), 0).r")
                .replace("texelFetch(CloudFaces, index + 1).r",
                        "texelFetch(CloudFaces, ivec2((index + 1) % 4096, (index + 1) / 4096), 0).r")
                .replace("texelFetch(CloudFaces, index + 2).r",
                        "texelFetch(CloudFaces, ivec2((index + 2) % 4096, (index + 2) / 4096), 0).r")
                .replace("textureLod(Sprite, texCoord0, MipMapLevel)", "textureLod(Sprite, texCoord0, float(MipMapLevel))")
                .replace("textureLod(CurrentSprite, texCoord0, MipMapLevel)", "textureLod(CurrentSprite, texCoord0, float(MipMapLevel))")
                .replace("textureLod(NextSprite, texCoord0, MipMapLevel)", "textureLod(NextSprite, texCoord0, float(MipMapLevel))")
                .replace("(gl_VertexID >> 3) / 1000.0", "float(gl_VertexID >> 3) / 1000.0")
                // ModelEngine's entity shader relies on desktop GLSL implicit int-to-float conversion.
                .replace("UV0 * SKINRES", "UV0 * float(SKINRES)")
                .replace("SPACING * (partId + 1)", "SPACING * float(partId + 1)")
                .replace("(1 - fade)", "(1.0 - fade)");
        return stripDesktopFloatSuffixes(translated);
    }

    private static String stripDesktopFloatSuffixes(String source) {
        StringBuilder result = null;
        int length = source.length();
        for (int i = 0; i < length; i++) {
            char current = source.charAt(i);
            if ((current == 'f' || current == 'F') && i > 0 && isNumberSuffixStart(source.charAt(i - 1))
                    && (i + 1 == length || !isIdentifierPart(source.charAt(i + 1)))) {
                if (result == null) {
                    result = new StringBuilder(source.length());
                    result.append(source, 0, i);
                }
                continue;
            }
            if (result != null) {
                result.append(current);
            }
        }
        return result == null ? source : result.toString();
    }

    private static boolean isNumberSuffixStart(char character) {
        return (character >= '0' && character <= '9') || character == '.';
    }

    private static boolean isIdentifierPart(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '_';
    }

    @JSBody(params = {"shader", "source"}, script = """
            window.__gaiusWebGL.shaderSource(window.__gaiusGL.shaders.get(shader),source);
            """)
    private static native void shaderSourceJs(int shader, String source);

    @JSBody(params = {"shader"}, script = """
            window.__gaiusWebGL.compileShader(window.__gaiusGL.shaders.get(shader));
            """)
    public static native void compileShader(int shader);

    @JSBody(params = {"program", "shader"}, script = """
            window.__gaiusWebGL.attachShader(
              window.__gaiusGL.programs.get(program),window.__gaiusGL.shaders.get(shader));
            """)
    public static native void attachShader(int program, int shader);

    public static void bindAttribLocation(int program, int index, CharSequence name) {
        bindAttribLocationJs(program, index, name.toString());
    }

    @JSBody(params = {"program", "index", "name"}, script = """
            window.__gaiusWebGL.bindAttribLocation(window.__gaiusGL.programs.get(program),index,name);
            """)
    private static native void bindAttribLocationJs(int program, int index, String name);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL;
            if (state.clearProgramUniforms) state.clearProgramUniforms(program|0);
            window.__gaiusWebGL.linkProgram(state.programs.get(program));
            state.refreshProgramAttribs(program|0);
            state.programVersion=((state.programVersion||0)+1)|0;
            state.bumpDrawProgramGeneration();
            """)
    public static native void linkProgram(int program);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL;
            if ((state.currentProgram|0)===(program|0)) {
              return;
            }
            state.currentProgram=program|0;
            state.bumpDrawProgramGeneration();
            window.__gaiusWebGL.useProgram(program===0?null:state.programs.get(program));
            """)
    public static native void useProgram(int program);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL, object=state.programs.get(program);
            if (state.clearProgramUniforms) state.clearProgramUniforms(program|0);
            if ((state.currentProgram|0)===(program|0)) {
              window.__gaiusWebGL.useProgram(null);
              state.currentProgram=0;
              state.bumpDrawProgramGeneration();
            }
            if (object) window.__gaiusWebGL.deleteProgram(object);
            state.programs.delete(program);
            state.programAttribs.delete(program|0);
            """)
    public static native void deleteProgram(int program);

    @JSBody(params = {"shader"}, script = """
            const state=window.__gaiusGL, object=state.shaders.get(shader);
            if (object) window.__gaiusWebGL.deleteShader(object); state.shaders.delete(shader);
            """)
    public static native void deleteShader(int shader);

    @JSBody(params = {"program", "parameter"}, script = """
            const value=window.__gaiusWebGL.getProgramParameter(
              window.__gaiusGL.programs.get(program),parameter);
            return typeof value==='boolean'?(value?1:0):(value|0);
            """)
    public static native int getProgrami(int program, int parameter);

    @JSBody(params = {"shader", "parameter"}, script = """
            const value=window.__gaiusWebGL.getShaderParameter(
              window.__gaiusGL.shaders.get(shader),parameter);
            return typeof value==='boolean'?(value?1:0):(value|0);
            """)
    public static native int getShaderi(int shader, int parameter);

    @JSBody(params = {"program", "maximumLength"}, script = """
            return window.__gaiusWebGL.getProgramInfoLog(window.__gaiusGL.programs.get(program))||'';
            """)
    public static native String getProgramInfoLog(int program, int maximumLength);

    @JSBody(params = {"shader", "maximumLength"}, script = """
            return window.__gaiusWebGL.getShaderInfoLog(window.__gaiusGL.shaders.get(shader))||'';
            """)
    public static native String getShaderInfoLog(int shader, int maximumLength);

    public static int getUniformLocation(int program, CharSequence name) {
        return getUniformLocationJs(program, name.toString());
    }

    @JSBody(params = {"program", "name"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            let locations=state.programUniformLocations.get(program|0);
            if (locations && locations.has(name)) return locations.get(name)|0;
            const object=gl.getUniformLocation(state.programs.get(program),name);
            if (object===null) return -1;
            const id=state.next++;
            if (!state.uniforms) state.uniforms=new Map();
            state.uniforms.set(id,object);
            state.uniformPrograms.set(id,program|0);
            if (!locations) {
              locations=new Map();
              state.programUniformLocations.set(program|0,locations);
            }
            locations.set(name,id|0);
            return id|0;
            """)
    private static native int getUniformLocationJs(int program, String name);

    @JSBody(params = {"location", "value"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined) {
              if (state.uniform1iValues.has(location|0)
                  && (state.uniform1iValues.get(location|0)|0)===(value|0)) return;
              window.__gaiusWebGL.uniform1i(object,value);
              state.uniform1iValues.set(location|0,value|0);
              state.uniformValueCache.delete(location|0);
            }
            """)
    public static native void uniform1i(int location, int value);

    @JSBody(params = {"location", "value"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined) {
              if (state.uniform1fValues.has(location|0)
                  && Object.is(state.uniform1fValues.get(location|0),value)) return;
              window.__gaiusWebGL.uniform1f(object,value);
              state.uniform1fValues.set(location|0,value);
              state.uniformValueCache.delete(location|0);
            }
            """)
    public static native void uniform1f(int location, float value);

    @JSBody(params = {"location", "x", "y"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformScalarsChanged(location,2,2,x,y,0,0)) {
              window.__gaiusWebGL.uniform2f(object,x,y);
            }
            """)
    public static native void uniform2f(int location, float x, float y);

    @JSBody(params = {"location", "x", "y", "z"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformScalarsChanged(location,3,3,x,y,z,0)) {
              window.__gaiusWebGL.uniform3f(object,x,y,z);
            }
            """)
    public static native void uniform3f(int location, float x, float y, float z);

    @JSBody(params = {"location", "x", "y", "z", "w"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformScalarsChanged(location,4,4,x,y,z,w)) {
              window.__gaiusWebGL.uniform4f(object,x,y,z,w);
            }
            """)
    public static native void uniform4f(int location, float x, float y, float z, float w);

    @JSBody(params = {"location", "x", "y"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformScalarsChanged(location,12,2,x,y,0,0)) {
              window.__gaiusWebGL.uniform2i(object,x,y);
            }
            """)
    public static native void uniform2i(int location, int x, int y);

    @JSBody(params = {"location", "x", "y", "z"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformScalarsChanged(location,13,3,x,y,z,0)) {
              window.__gaiusWebGL.uniform3i(object,x,y,z);
            }
            """)
    public static native void uniform3i(int location, int x, int y, int z);

    @JSBody(params = {"location", "x", "y", "z", "w"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformScalarsChanged(location,14,4,x,y,z,w)) {
              window.__gaiusWebGL.uniform4i(object,x,y,z,w);
            }
            """)
    public static native void uniform4i(int location, int x, int y, int z, int w);

    public static void uniform1fv(int location, FloatBuffer values) {
        uniform1fvJs(location, floats(values));
    }

    public static void uniform2fv(int location, FloatBuffer values) {
        uniform2fvJs(location, floats(values));
    }

    public static void uniform3fv(int location, FloatBuffer values) {
        uniform3fvJs(location, floats(values));
    }

    public static void uniform4fv(int location, FloatBuffer values) {
        uniform4fvJs(location, floats(values));
    }

    public static void uniform1iv(int location, IntBuffer values) {
        uniform1ivJs(location, ints(values));
    }

    public static void uniform2iv(int location, IntBuffer values) {
        uniform2ivJs(location, ints(values));
    }

    public static void uniform3iv(int location, IntBuffer values) {
        uniform3ivJs(location, ints(values));
    }

    public static void uniform4iv(int location, IntBuffer values) {
        uniform4ivJs(location, ints(values));
    }

    public static void uniformMatrix2fv(int location, boolean transpose, FloatBuffer values) {
        uniformMatrix2fvJs(location, transpose, floats(values));
    }

    public static void uniformMatrix3fv(int location, boolean transpose, FloatBuffer values) {
        uniformMatrix3fvJs(location, transpose, floats(values));
    }

    public static void uniformMatrix4fv(int location, boolean transpose, FloatBuffer values) {
        uniformMatrix4fvJs(location, transpose, floats(values));
    }

    public static void uniform1fv(int location, float[] values) {
        uniform1fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform2fv(int location, float[] values) {
        uniform2fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform3fv(int location, float[] values) {
        uniform3fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform4fv(int location, float[] values) {
        uniform4fvJs(location, Float32Array.fromJavaArray(values));
    }

    public static void uniform1iv(int location, int[] values) {
        uniform1ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniform2iv(int location, int[] values) {
        uniform2ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniform3iv(int location, int[] values) {
        uniform3ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniform4iv(int location, int[] values) {
        uniform4ivJs(location, Int32Array.fromJavaArray(values));
    }

    public static void uniformMatrix2fv(int location, boolean transpose, float[] values) {
        uniformMatrix2fvJs(location, transpose, Float32Array.fromJavaArray(values));
    }

    public static void uniformMatrix3fv(int location, boolean transpose, float[] values) {
        uniformMatrix3fvJs(location, transpose, Float32Array.fromJavaArray(values));
    }

    public static void uniformMatrix4fv(int location, boolean transpose, float[] values) {
        uniformMatrix4fvJs(location, transpose, Float32Array.fromJavaArray(values));
    }

    public static void uniform1fv(int location, int count, long values) {
        uniform1fvJs(location, pointerFloats(values, count));
    }

    public static void uniform2fv(int location, int count, long values) {
        uniform2fvJs(location, pointerFloats(values, count * 2));
    }

    public static void uniform3fv(int location, int count, long values) {
        uniform3fvJs(location, pointerFloats(values, count * 3));
    }

    public static void uniform4fv(int location, int count, long values) {
        uniform4fvJs(location, pointerFloats(values, count * 4));
    }

    public static void uniform1iv(int location, int count, long values) {
        uniform1ivJs(location, pointerInts(values, count));
    }

    public static void uniform2iv(int location, int count, long values) {
        uniform2ivJs(location, pointerInts(values, count * 2));
    }

    public static void uniform3iv(int location, int count, long values) {
        uniform3ivJs(location, pointerInts(values, count * 3));
    }

    public static void uniform4iv(int location, int count, long values) {
        uniform4ivJs(location, pointerInts(values, count * 4));
    }

    public static void uniformMatrix2fv(int location, int count, boolean transpose, long values) {
        uniformMatrix2fvJs(location, transpose, pointerFloats(values, count * 4));
    }

    public static void uniformMatrix3fv(int location, int count, boolean transpose, long values) {
        uniformMatrix3fvJs(location, transpose, pointerFloats(values, count * 9));
    }

    public static void uniformMatrix4fv(int location, int count, boolean transpose, long values) {
        uniformMatrix4fvJs(location, transpose, pointerFloats(values, count * 16));
    }

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined) {
              if (state.uniformArrayChanged(location,101,false,values)) {
                window.__gaiusWebGL.uniform1fv(object,values);
              }
            }
            """)
    private static native void uniform1fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,102,false,values)) {
              window.__gaiusWebGL.uniform2fv(object,values);
            }
            """)
    private static native void uniform2fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,103,false,values)) {
              window.__gaiusWebGL.uniform3fv(object,values);
            }
            """)
    private static native void uniform3fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,104,false,values)) {
              window.__gaiusWebGL.uniform4fv(object,values);
            }
            """)
    private static native void uniform4fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined) {
              if (state.uniformArrayChanged(location,201,false,values)) {
                window.__gaiusWebGL.uniform1iv(object,values);
              }
            }
            """)
    private static native void uniform1ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,202,false,values)) {
              window.__gaiusWebGL.uniform2iv(object,values);
            }
            """)
    private static native void uniform2ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,203,false,values)) {
              window.__gaiusWebGL.uniform3iv(object,values);
            }
            """)
    private static native void uniform3ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,204,false,values)) {
              window.__gaiusWebGL.uniform4iv(object,values);
            }
            """)
    private static native void uniform4ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,302,transpose,values)) {
              window.__gaiusWebGL.uniformMatrix2fv(object,transpose,values);
            }
            """)
    private static native void uniformMatrix2fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,303,transpose,values)) {
              window.__gaiusWebGL.uniformMatrix3fv(object,transpose,values);
            }
            """)
    private static native void uniformMatrix3fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const state=window.__gaiusGL;
            const object=state.uniforms&&state.uniforms.get(location);
            if (object!==undefined && state.uniformArrayChanged(location,304,transpose,values)) {
              window.__gaiusWebGL.uniformMatrix4fv(object,transpose,values);
            }
            """)
    private static native void uniformMatrix4fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"index"}, script = """
            const state=window.__gaiusGL,vao=state.getVaoEmu(),idx=index|0;
            if (vao.enabledAttribs.has(idx)) {
              const hasBuffer=vao.attribHasBuffer.has(idx);
              if (!hasBuffer) {
                vao.missingEnabledAttribs.add(idx);
              } else {
                vao.missingEnabledAttribs.delete(idx);
              }
              if (state.hotPathTelemetryEnabled) {
                const skips=((state.enableAttribFastSkips||0)+1)|0;
                state.enableAttribFastSkips=skips;
                if ((skips & 255)===0) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.enableAttribFastSkips=skips;
                }
              }
              return;
            }
            vao.enabledAttribs.add(idx);
            if (!vao.attribHasBuffer.has(idx)) {
              vao.missingEnabledAttribs.add(idx);
            } else {
              vao.missingEnabledAttribs.delete(idx);
            }
            state.bumpVaoAttribVersion(vao);
            state.bumpVaoAttribTypeVersion(vao);
            window.__gaiusWebGL.enableVertexAttribArray(index);
            """)
    public static native void enableVertexAttribArray(int index);

    @JSBody(params = {"index"}, script = """
            const state=window.__gaiusGL,vao=state.getVaoEmu(),idx=index|0;
            if (!vao.enabledAttribs.has(idx)) {
              if (vao.missingEnabledAttribs.delete(idx)) {
                state.bumpVaoAttribVersion(vao);
              } else {
                if (state.hotPathTelemetryEnabled) {
                  const skips=((state.disableAttribFastSkips||0)+1)|0;
                  state.disableAttribFastSkips=skips;
                  if ((skips & 255)===0) {
                    var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.disableAttribFastSkips=skips;
                  }
                }
              }
              return;
            }
            vao.enabledAttribs.delete(idx);
            vao.missingEnabledAttribs.delete(idx);
            state.bumpVaoAttribVersion(vao);
            state.bumpVaoAttribTypeVersion(vao);
            window.__gaiusWebGL.disableVertexAttribArray(index);
            """)
    public static native void disableVertexAttribArray(int index);

    @JSBody(params = {"index", "size", "type", "normalized", "stride", "offset"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,vao=state.getVaoEmu();
            const idx=index|0;
            const buffer=state.boundBuffers.get(gl.ARRAY_BUFFER)|0;
            const sizeValue=size|0;
            const typeValue=type|0;
            const expectedInteger=state.expectedAttribInteger(idx);
            const effectiveInteger=expectedInteger===null ? false : !!expectedInteger;
            const normalizedValue=effectiveInteger ? false : !!normalized;
            const strideValue=stride|0;
            const numericOffset=Number(offset);
            const numericStride=strideValue;
            const componentBytes=state.componentBytes(typeValue);
            const aligned=(numericOffset % componentBytes)===0
              && (numericStride===0 || (numericStride % componentBytes)===0);
            const misaligned=!aligned;
            const present=!!buffer;
            const previousPointer=vao.attribPointers.get(idx);
            const previousMisaligned=vao.misalignedAttribs && vao.misalignedAttribs.has(idx);
            const previousPresence=vao.attribHasBuffer.has(idx);
            const typeLayoutChanged=!previousPointer
              || !!previousPointer.integer!==effectiveInteger
              || (previousPointer.size|0)!==sizeValue
              || (previousPointer.type|0)!==typeValue;
            const samePointer=!!previousPointer
              && (previousPointer.index|0)===idx
              && (previousPointer.size|0)===sizeValue
              && (previousPointer.type|0)===typeValue
              && !!previousPointer.normalized===normalizedValue
              && (previousPointer.stride|0)===strideValue
              && Number(previousPointer.offset)===numericOffset
              && !!previousPointer.integer===effectiveInteger
              && (previousPointer.buffer|0)===(buffer|0);
            if (samePointer && previousMisaligned===misaligned && previousPresence===present) {
              if (state.hotPathTelemetryEnabled) {
                const skips=((state.attribPointerFastSkips||0)+1)|0;
                state.attribPointerFastSkips=skips;
                if ((skips & 255)===0) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.attribPointerFastSkips=skips;
                }
              }
              return;
            }
            state.recordAttribPointerAdapt(idx,false,effectiveInteger,typeValue);
            const pointer={
              index:idx,
              size:sizeValue,
              type:typeValue,
              normalized:normalizedValue,
              stride:strideValue,
              offset:numericOffset,
              integer:effectiveInteger,
              buffer:buffer|0
            };
            vao.attribPointers.set(idx,pointer);
            state.replaceVaoBufferRef(
              vao,previousPointer?(previousPointer.buffer|0):0,buffer|0);
            state.setAttribBufferPresence(vao,idx,present);
            state.setAttribMisaligned(vao,idx,misaligned,buffer|0);
            if (typeLayoutChanged) state.bumpVaoAttribTypeVersion(vao);
            const validationChanged=!samePointer
              || previousMisaligned!==misaligned
              || previousPresence!==present;
            if (!previousPresence && !buffer) {
              // setAttribBufferPresence already left the missing-buffer state unchanged.
            } else if (validationChanged) {
              if (previousPresence===present) state.bumpVaoAttribVersion(vao);
            } else {
              if (aligned) return;
            }
            if (aligned) {
              if (effectiveInteger) {
                gl.vertexAttribIPointer(index,sizeValue,typeValue,strideValue,numericOffset);
              } else {
                gl.vertexAttribPointer(index,sizeValue,typeValue,normalizedValue,strideValue,numericOffset);
              }
            } else {
              if (buffer) state.markBufferShadowRequired(buffer,'misaligned-attrib-pointer');
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
            }
            """)
    private static native void vertexAttribPointerJs(
            int index, int size, int type, boolean normalized, int stride, int offset);

    public static void vertexAttribPointer(
            int index, int size, int type, boolean normalized, int stride, long offset) {
        vertexAttribPointerJs(index, size, type, normalized, stride, (int) offset);
    }

    @JSBody(params = {"index", "size", "type", "stride", "offset"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,vao=state.getVaoEmu();
            const idx=index|0;
            const buffer=state.boundBuffers.get(gl.ARRAY_BUFFER)|0;
            const sizeValue=size|0;
            const typeValue=type|0;
            const expectedInteger=state.expectedAttribInteger(idx);
            const effectiveInteger=expectedInteger===null ? true : !!expectedInteger;
            const strideValue=stride|0;
            const numericOffset=Number(offset);
            const numericStride=strideValue;
            const componentBytes=state.componentBytes(typeValue);
            const aligned=(numericOffset % componentBytes)===0
              && (numericStride===0 || (numericStride % componentBytes)===0);
            const misaligned=!aligned;
            const present=!!buffer;
            const previousPointer=vao.attribPointers.get(idx);
            const previousMisaligned=vao.misalignedAttribs && vao.misalignedAttribs.has(idx);
            const previousPresence=vao.attribHasBuffer.has(idx);
            const typeLayoutChanged=!previousPointer
              || !!previousPointer.integer!==effectiveInteger
              || (previousPointer.size|0)!==sizeValue
              || (previousPointer.type|0)!==typeValue;
            const samePointer=!!previousPointer
              && (previousPointer.index|0)===idx
              && (previousPointer.size|0)===sizeValue
              && (previousPointer.type|0)===typeValue
              && !previousPointer.normalized
              && (previousPointer.stride|0)===strideValue
              && Number(previousPointer.offset)===numericOffset
              && !!previousPointer.integer===effectiveInteger
              && (previousPointer.buffer|0)===(buffer|0);
            if (samePointer && previousMisaligned===misaligned && previousPresence===present) {
              if (state.hotPathTelemetryEnabled) {
                const skips=((state.attribPointerFastSkips||0)+1)|0;
                state.attribPointerFastSkips=skips;
                if ((skips & 255)===0) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.attribPointerFastSkips=skips;
                }
              }
              return;
            }
            state.recordAttribPointerAdapt(idx,true,effectiveInteger,typeValue);
            const pointer={
              index:idx,
              size:sizeValue,
              type:typeValue,
              normalized:false,
              stride:strideValue,
              offset:numericOffset,
              integer:effectiveInteger,
              buffer:buffer|0
            };
            vao.attribPointers.set(idx,pointer);
            state.replaceVaoBufferRef(
              vao,previousPointer?(previousPointer.buffer|0):0,buffer|0);
            state.setAttribBufferPresence(vao,idx,present);
            state.setAttribMisaligned(vao,idx,misaligned,buffer|0);
            if (typeLayoutChanged) state.bumpVaoAttribTypeVersion(vao);
            const validationChanged=!samePointer
              || previousMisaligned!==misaligned
              || previousPresence!==present;
            if (!previousPresence && !buffer) {
              // setAttribBufferPresence already left the missing-buffer state unchanged.
            } else if (validationChanged) {
              if (previousPresence===present) state.bumpVaoAttribVersion(vao);
            } else {
              if (aligned) return;
            }
            if (aligned) {
              if (effectiveInteger) {
                gl.vertexAttribIPointer(index,sizeValue,typeValue,strideValue,numericOffset);
              } else {
                gl.vertexAttribPointer(index,sizeValue,typeValue,false,strideValue,numericOffset);
              }
            } else {
              if (buffer) state.markBufferShadowRequired(buffer,'misaligned-attrib-pointer');
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
            }
            """)
    private static native void vertexAttribIPointerJs(
            int index, int size, int type, int stride, int offset);

    public static void vertexAttribIPointer(
            int index, int size, int type, int stride, long offset) {
        vertexAttribIPointerJs(index, size, type, stride, (int) offset);
    }

    @JSBody(params = {"index", "divisor"}, script = "window.__gaiusWebGL.vertexAttribDivisor(index,divisor);")
    public static native void vertexAttribDivisor(int index, int divisor);

    @JSBody(params = {"binding", "buffer", "offset", "stride"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            const key=binding|0;
            const previous=vao.vertexBuffers.get(key);
            const previousBuffer=previous?(previous.buffer|0):0;
            if ((buffer|0)===0) {
              if (!previous) return;
              vao.vertexBuffers.delete(key);
              vao.attribFormats.forEach(function(_format, attrib) {
                const attribBinding=vao.attribBindings.has(attrib) ? (vao.attribBindings.get(attrib)|0) : (attrib|0);
                if (attribBinding===key) {
                  state.setAttribBufferPresence(vao,attrib|0,false);
                  state.setAttribMisaligned(vao,attrib|0,false);
                }
              });
            } else {
              const nextOffset=Number(offset);
              const nextStride=stride|0;
              if (previous
                  && (previous.buffer|0)===(buffer|0)
                  && Number(previous.offset)===nextOffset
                  && (previous.stride|0)===nextStride) {
                if (state.hotPathTelemetryEnabled) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.vertexBufferFastSkips=(stats.vertexBufferFastSkips||0)+1;
                }
                return;
              }
              vao.vertexBuffers.set(key,{
                buffer: buffer|0,
                offset: nextOffset,
                stride: nextStride
              });
            }
            state.replaceVaoBufferRef(vao,previousBuffer,buffer|0);
            state.applyVertexBinding(key);
            """)
    private static native void bindVertexBufferJs(int binding, int buffer, int offset, int stride);

    public static void bindVertexBuffer(int binding, int buffer, long offset, int stride) {
        bindVertexBufferJs(binding, buffer, (int) offset, stride);
    }

    @JSBody(params = {"index", "binding"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            const idx=index|0;
            const next=binding|0;
            const previous=vao.attribBindings.has(idx) ? (vao.attribBindings.get(idx)|0) : idx;
            if (previous===next) {
              if (state.hotPathTelemetryEnabled) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.attribBindingFastSkips=(stats.attribBindingFastSkips||0)+1;
              }
              return;
            }
            vao.attribBindings.set(idx,next);
            state.applyAttribBinding(idx);
            """)
    public static native void vertexAttribBinding(int index, int binding);

    @JSBody(params = {"index", "size", "type", "normalized", "relativeOffset"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            const idx=index|0;
            const previous=vao.attribFormats.get(idx);
            if (previous
                && (previous.size|0)===(size|0)
                && (previous.type|0)===(type|0)
                && !!previous.normalized===!!normalized
                && (previous.relativeOffset|0)===(relativeOffset|0)
                && !previous.integer) {
              if (state.hotPathTelemetryEnabled) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.attribFormatFastSkips=(stats.attribFormatFastSkips||0)+1;
              }
              return;
            }
            vao.attribFormats.set(idx,{
              size: size|0,
              type: type|0,
              normalized: !!normalized,
              relativeOffset: relativeOffset|0,
              integer: false
            });
            state.applyAttribBinding(idx);
            """)
    public static native void vertexAttribFormat(
            int index, int size, int type, boolean normalized, int relativeOffset);

    @JSBody(params = {"index", "size", "type", "relativeOffset"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            const idx=index|0;
            const previous=vao.attribFormats.get(idx);
            if (previous
                && (previous.size|0)===(size|0)
                && (previous.type|0)===(type|0)
                && !previous.normalized
                && (previous.relativeOffset|0)===(relativeOffset|0)
                && !!previous.integer) {
              if (state.hotPathTelemetryEnabled) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.attribFormatFastSkips=(stats.attribFormatFastSkips||0)+1;
              }
              return;
            }
            vao.attribFormats.set(idx,{
              size: size|0,
              type: type|0,
              normalized: false,
              relativeOffset: relativeOffset|0,
              integer: true
            });
            state.applyAttribBinding(idx);
            """)
    public static native void vertexAttribIFormat(
            int index, int size, int type, int relativeOffset);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.vaos.set(id,window.__gaiusWebGL.createVertexArray());
            state.vaoEmu.set(id,state.assignVaoIdentity(state.newVaoEmu(),id|0));
            return id|0;
            """)
    public static native int genVertexArray();

    @JSBody(params = {"array"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if ((state.currentVaoId|0)===(array|0)) {
              return;
            }
            state.currentVaoId=array|0;
            state.currentVaoCacheId=array|0;
            state.currentVaoCache=state.vaoEmu.get(array|0) || null;
            gl.bindVertexArray(array===0?null:state.vaos.get(array));
            const vao=state.currentVaoCache || state.getVaoEmu();
            state.boundBuffers.set(gl.ELEMENT_ARRAY_BUFFER,vao.elementArrayBuffer|0);
            """)
    public static native void bindVertexArray(int array);

    @JSBody(params = {"array"}, script = """
            const state=window.__gaiusGL, object=state.vaos.get(array);
            if (object) window.__gaiusWebGL.deleteVertexArray(object); state.vaos.delete(array);
            const vao=state.vaoEmu.get(array);
            state.releaseVaoMisalignedBuffers(vao);
            state.releaseVaoBufferRefs(vao);
            state.releaseVaoPhysicalElementBuffer(vao);
            state.releaseVaoAlignedAttribRefs(vao);
            state.releaseVaoShiftedIndexRefs(vao);
            state.vaoEmu.delete(array);
            if (state.currentVaoId===(array|0)) {
              state.currentVaoId=0;
              state.currentVaoCacheId=-1;
              state.currentVaoCache=null;
              const defaultVao=state.getVaoEmu();
              state.boundBuffers.set(
                window.__gaiusWebGL.ELEMENT_ARRAY_BUFFER,defaultVao.elementArrayBuffer|0);
            } else if (state.currentVaoCacheId===(array|0)) {
              state.currentVaoCacheId=-1;
              state.currentVaoCache=null;
            }
            """)
    public static native void deleteVertexArray(int array);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.framebuffers.set(id,window.__gaiusWebGL.createFramebuffer()); return id|0;
            """)
    public static native int genFramebuffer();

    public static int createFramebuffer() {
        return genFramebuffer();
    }

    @JSBody(params = {"target", "framebuffer"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (state.guiItemOffscreenScissorDisabled) {
              state.restoreGuiItemOffscreenScissor('bindFramebuffer');
            }
            const next=framebuffer|0;
            if (target===gl.FRAMEBUFFER
                && (state.framebufferBindings.draw|0)===next
                && (state.framebufferBindings.read|0)===next) return;
            if (target===gl.DRAW_FRAMEBUFFER
                && (state.framebufferBindings.draw|0)===next) return;
            if (target===gl.READ_FRAMEBUFFER
                && (state.framebufferBindings.read|0)===next) return;
            gl.bindFramebuffer(target,framebuffer===0?null:state.framebuffers.get(framebuffer));
            if (target===0x8D40) {
              state.framebufferBindings.draw=framebuffer|0;
              state.framebufferBindings.read=framebuffer|0;
              state.setDrawFramebufferCache(framebuffer|0);
            } else if (target===0x8CA9) {
              state.framebufferBindings.draw=framebuffer|0;
              state.setDrawFramebufferCache(framebuffer|0);
            } else if (target===0x8CA8) {
              state.framebufferBindings.read=framebuffer|0;
            }
            """)
    public static native void bindFramebuffer(int target, int framebuffer);

    @JSBody(params = {"target", "attachment", "textureTarget", "texture", "level"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            gl.framebufferTexture2D(
              target,attachment,textureTarget,texture===0?null:state.textures.get(texture),level);
            if ((attachment|0)===gl.COLOR_ATTACHMENT0) {
              let framebuffer=0;
              if (target===gl.FRAMEBUFFER || target===gl.DRAW_FRAMEBUFFER) {
                framebuffer=state.framebufferBindings.draw|0;
              } else if (target===gl.READ_FRAMEBUFFER) {
                framebuffer=state.framebufferBindings.read|0;
              }
              if (framebuffer) {
                if (state.framebufferColorTextureMisses) state.framebufferColorTextureMisses.delete(framebuffer|0);
                if (texture) state.framebufferColorTextures.set(framebuffer|0,texture|0);
                else {
                  state.framebufferColorTextures.delete(framebuffer|0);
                  if (state.framebufferColorTextureMisses) state.framebufferColorTextureMisses.add(framebuffer|0);
                }
                state.refreshFramebufferOffscreen512(framebuffer|0);
              }
            }
            """)
    public static native void framebufferTexture2D(
            int target, int attachment, int textureTarget, int texture, int level);

    @JSBody(params = {"target"}, script = """
            return window.__gaiusWebGL.checkFramebufferStatus(target)|0;
            """)
    public static native int checkFramebufferStatus(int target);

    @JSBody(params = {"framebuffer", "attachment", "texture", "level"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousDraw=state.framebufferBindings.draw|0;
            const previousRead=state.framebufferBindings.read|0;
            gl.bindFramebuffer(gl.FRAMEBUFFER,framebuffer===0?null:state.framebuffers.get(framebuffer));
            gl.framebufferTexture2D(
              gl.FRAMEBUFFER,attachment,gl.TEXTURE_2D,texture===0?null:state.textures.get(texture),level);
            if ((attachment|0)===gl.COLOR_ATTACHMENT0 && (framebuffer|0)!==0) {
              if (state.framebufferColorTextureMisses) state.framebufferColorTextureMisses.delete(framebuffer|0);
              if (texture) state.framebufferColorTextures.set(framebuffer|0,texture|0);
              else {
                state.framebufferColorTextures.delete(framebuffer|0);
                if (state.framebufferColorTextureMisses) state.framebufferColorTextureMisses.add(framebuffer|0);
              }
              state.refreshFramebufferOffscreen512(framebuffer|0);
            }
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:state.framebuffers.get(previousRead));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:state.framebuffers.get(previousDraw));
            """)
    public static native void namedFramebufferTexture(
            int framebuffer, int attachment, int texture, int level);

    @JSBody(params = {"framebuffer", "target"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousDraw=state.framebufferBindings.draw|0;
            const previousRead=state.framebufferBindings.read|0;
            gl.bindFramebuffer(target,framebuffer===0?null:state.framebuffers.get(framebuffer));
            const status=gl.checkFramebufferStatus(target)|0;
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:state.framebuffers.get(previousRead));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:state.framebuffers.get(previousDraw));
            return status;
            """)
    public static native int checkNamedFramebufferStatus(int framebuffer, int target);

    @JSBody(params = {"framebuffer"}, script = """
            const state=window.__gaiusGL, object=state.framebuffers.get(framebuffer);
            if (object) window.__gaiusWebGL.deleteFramebuffer(object); state.framebuffers.delete(framebuffer);
            state.framebufferColorTextures.delete(framebuffer|0);
            if (state.framebufferColorTextureMisses) state.framebufferColorTextureMisses.delete(framebuffer|0);
            state.offscreen512Framebuffers.delete(framebuffer|0);
            if (state.framebufferBindings.draw===framebuffer) {
              state.framebufferBindings.draw=0;
              state.setDrawFramebufferCache(0);
            }
            if (state.framebufferBindings.read===framebuffer) state.framebufferBindings.read=0;
            """)
    public static native void deleteFramebuffer(int framebuffer);

    @JSBody(params = {
            "sourceX0", "sourceY0", "sourceX1", "sourceY1",
            "targetX0", "targetY0", "targetX1", "targetY1", "mask", "filter"
    }, script = """
            const state=window.__gaiusGL;
            if (state) state.ensureDefaultFramebufferColorWrites(mask);
            window.__gaiusWebGL.blitFramebuffer(
              sourceX0,sourceY0,sourceX1,sourceY1,targetX0,targetY0,targetX1,targetY1,mask,filter);
            """)
    public static native void blitFramebuffer(
            int sourceX0, int sourceY0, int sourceX1, int sourceY1,
            int targetX0, int targetY0, int targetX1, int targetY1,
            int mask, int filter);

    @JSBody(params = {
            "readFramebuffer", "drawFramebuffer",
            "sourceX0", "sourceY0", "sourceX1", "sourceY1",
            "targetX0", "targetY0", "targetX1", "targetY1", "mask", "filter"
    }, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousDraw=state.framebufferBindings.draw|0;
            const previousRead=state.framebufferBindings.read|0;
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,readFramebuffer===0?null:state.framebuffers.get(readFramebuffer));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,drawFramebuffer===0?null:state.framebuffers.get(drawFramebuffer));
            state.ensureColorWritesForFramebuffer(drawFramebuffer|0,mask);
            gl.blitFramebuffer(
              sourceX0,sourceY0,sourceX1,sourceY1,targetX0,targetY0,targetX1,targetY1,mask,filter);
            gl.bindFramebuffer(gl.READ_FRAMEBUFFER,previousRead===0?null:state.framebuffers.get(previousRead));
            gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,previousDraw===0?null:state.framebuffers.get(previousDraw));
            """)
    public static native void blitNamedFramebuffer(
            int readFramebuffer, int drawFramebuffer,
            int sourceX0, int sourceY0, int sourceX1, int sourceY1,
            int targetX0, int targetY0, int targetX1, int targetY1,
            int mask, int filter);

    @JSBody(params = {"mode", "first", "count", "instances"}, script = """
            window.__gaiusGL.executeDraw(2,mode,first,count,instances,0,0);
            """)
    public static native void drawArraysInstanced(int mode, int first, int count, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "instances"}, script = """
            window.__gaiusGL.executeDraw(3,mode,count,type,offset,instances,0);
            """)
    private static native void drawElementsInstancedJs(
            int mode, int count, int type, int offset, int instances);

    public static void drawElementsInstanced(
            int mode, int count, int type, long offset, int instances) {
        drawElementsInstancedJs(mode, count, type, (int) offset, instances);
    }

    @JSBody(params = {"mode", "count", "type", "offset", "baseVertex"}, script = """
            window.__gaiusGL.executeDraw(4,mode,count,type,offset,baseVertex,0);
            """)
    private static native void drawElementsBaseVertexJs(
            int mode, int count, int type, int offset, int baseVertex);

    public static void drawElementsBaseVertex(
            int mode, int count, int type, long offset, int baseVertex) {
        drawElementsBaseVertexJs(mode, count, type, (int) offset, baseVertex);
    }

    @JSBody(params = {"mode", "count", "type", "offset", "instances", "baseVertex"}, script = """
            window.__gaiusGL.executeDraw(5,mode,count,type,offset,instances,baseVertex);
            """)
    private static native void drawElementsInstancedBaseVertexJs(
            int mode, int count, int type, int offset, int instances, int baseVertex);

    public static void drawElementsInstancedBaseVertex(
            int mode, int count, int type, long offset, int instances, int baseVertex) {
        drawElementsInstancedBaseVertexJs(mode, count, type, (int) offset, instances, baseVertex);
    }

    public static void drawFromBuffers(
            int mode, int firstOrBaseVertex, int indexOffset, int count,
            int type, int indexBytes, int instances, int elementBuffer) {
        drawFromBuffers(
                mode, firstOrBaseVertex, indexOffset, count,
                type, indexBytes, instances, elementBuffer, 0);
    }

    @JSBody(params = {
            "mode", "firstOrBaseVertex", "indexOffset", "count",
            "type", "indexBytes", "instances", "elementBuffer", "baseInstance"
    }, script = """
            const state=window.__gaiusGL;
            if ((indexBytes|0)===0) {
              if ((baseInstance|0)!==0) {
                state.executeDraw(
                  6,mode,firstOrBaseVertex,count,instances,baseInstance,0,0);
              } else {
                state.executeDraw(
                  (instances|0)>1?2:0,mode,firstOrBaseVertex,count,instances,0,0);
              }
              return;
            }
            const gl=window.__gaiusWebGL;
            const vao=state.getVaoEmu();
            const nextId=elementBuffer|0;
            const current=state.boundBuffers.get(gl.ELEMENT_ARRAY_BUFFER)|0;
            if ((vao.elementArrayBuffer|0)===nextId) {
              state.bindPhysicalElementBuffer(vao,vao.elementArrayBufferObject || null);
              if (current!==nextId) state.boundBuffers.set(gl.ELEMENT_ARRAY_BUFFER,nextId);
            } else {
              const object=nextId===0?null:state.buffers.get(nextId);
              state.bindPhysicalElementBuffer(vao,object || null);
              state.replaceVaoBufferRef(vao,vao.elementArrayBuffer|0,nextId);
              vao.elementArrayBuffer=nextId;
              vao.elementArrayBufferObject=object || null;
              if (current!==nextId) state.boundBuffers.set(gl.ELEMENT_ARRAY_BUFFER,nextId);
            }
            const offset=Number(indexOffset)*Number(indexBytes);
            if ((baseInstance|0)!==0) {
              state.executeDraw(
                7,mode,count,type,offset,instances,firstOrBaseVertex,baseInstance);
            } else if ((instances|0)>1) {
              if ((firstOrBaseVertex|0)!==0) {
                state.executeDraw(5,mode,count,type,offset,instances,firstOrBaseVertex);
              } else {
                state.executeDraw(3,mode,count,type,offset,instances,0);
              }
            } else if ((firstOrBaseVertex|0)!==0) {
              state.executeDraw(4,mode,count,type,offset,firstOrBaseVertex,0);
            } else {
              state.executeDraw(1,mode,count,type,offset,0,0);
            }
            """)
    public static native void drawFromBuffers(
            int mode, int firstOrBaseVertex, int indexOffset, int count,
            int type, int indexBytes, int instances, int elementBuffer, int baseInstance);

    @JSBody(params = {"target", "internalFormat", "buffer"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (target !== 0x8C2A) {
              return;
            }
            const unit=state.activeTextureUnit || 0;
            const keyBase=unit*65536;
            const texture=(state.textureBindings.get(keyBase+35882)
              || state.textureBindings.get(keyBase+(gl.TEXTURE_2D&65535)) || 0)|0;
            const object=texture===0?null:state.textures.get(texture);
            if (!object) {
              return;
            }
            const bytes=state.bufferBytes.get(buffer);
            const size=state.bufferSizes.get(buffer) || (bytes ? bytes.byteLength : 0);
            let byteLength=bytes ? bytes.byteLength : size;
            if (!Number.isFinite(byteLength) || byteLength < 0) byteLength=0;
            let webInternalFormat=internalFormat;
            let webFormat=gl.RED;
            let webType=gl.UNSIGNED_BYTE;
            let bytesPerTexel=1;
            let signedInteger=false;
            if (internalFormat === 0x8231 || internalFormat === 0x8229) {
              webInternalFormat=0x8231;
              webFormat=0x8D94;
              webType=gl.BYTE;
              signedInteger=true;
              bytesPerTexel=1;
            } else if (internalFormat === gl.RGBA8) {
              webInternalFormat=gl.RGBA8;
              webFormat=gl.RGBA;
              webType=gl.UNSIGNED_BYTE;
              bytesPerTexel=4;
            }
            const texels=Math.max(1,Math.ceil(byteLength / bytesPerTexel));
            const width=Math.max(1,Math.min(4096,texels));
            const height=Math.max(1,Math.ceil(texels / width));
            const paddedLength=width * height * bytesPerTexel;
            let upload;
            if (bytes) {
              let source=bytes;
              if (source.byteLength < paddedLength) {
                const padded=new Uint8Array(paddedLength);
                padded.set(source,0);
                source=padded;
              }
              upload=signedInteger
                ? new Int8Array(source.buffer,source.byteOffset || 0,paddedLength)
                : source;
              if (!signedInteger) {
                const unsignedStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                unsignedStats.texBufferUnsignedViewSkips=
                  (unsignedStats.texBufferUnsignedViewSkips||0)+1;
              }
            } else {
              upload=signedInteger ? new Int8Array(paddedLength) : new Uint8Array(paddedLength);
            }
            const previousAlignment=state.unpackAlignment|0;
            const previousRowLength=state.unpackRowLength|0;
            const previousSkipRows=state.unpackSkipRows|0;
            const previousSkipPixels=state.unpackSkipPixels|0;
            const changeAlignment=previousAlignment!==1;
            const changeRowLength=previousRowLength!==0;
            const changeSkipRows=previousSkipRows!==0;
            const changeSkipPixels=previousSkipPixels!==0;
            const changedPixelStores=(changeAlignment?1:0)+(changeRowLength?1:0)
              +(changeSkipRows?1:0)+(changeSkipPixels?1:0);
            const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
            stats.texBufferStateReadbacksAvoided=(stats.texBufferStateReadbacksAvoided||0)+5;
            stats.texBufferStateCallSkips=(stats.texBufferStateCallSkips||0)+(10-changedPixelStores*2);
            try {
              if (changeAlignment) gl.pixelStorei(gl.UNPACK_ALIGNMENT,1);
              if (changeRowLength) gl.pixelStorei(gl.UNPACK_ROW_LENGTH,0);
              if (changeSkipRows) gl.pixelStorei(gl.UNPACK_SKIP_ROWS,0);
              if (changeSkipPixels) gl.pixelStorei(gl.UNPACK_SKIP_PIXELS,0);
              state.applyTextureParameter(
                texture,gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
              state.applyTextureParameter(
                texture,gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
              state.applyTextureParameter(
                texture,gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
              state.applyTextureParameter(
                texture,gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
              gl.texImage2D(gl.TEXTURE_2D,0,webInternalFormat,width,height,0,webFormat,webType,upload);
            } finally {
              if (changeAlignment) gl.pixelStorei(gl.UNPACK_ALIGNMENT,previousAlignment);
              if (changeRowLength) gl.pixelStorei(gl.UNPACK_ROW_LENGTH,previousRowLength);
              if (changeSkipRows) gl.pixelStorei(gl.UNPACK_SKIP_ROWS,previousSkipRows);
              if (changeSkipPixels) gl.pixelStorei(gl.UNPACK_SKIP_PIXELS,previousSkipPixels);
            }
            state.textureBufferInfo.set(texture,{
              buffer: buffer,
              internalFormat: internalFormat,
              webInternalFormat: webInternalFormat,
              width: width,
              height: height,
              byteLength: byteLength,
              signedInteger: signedInteger,
              at: Date.now()
            });
            """)
    public static native void texBuffer(int target, int internalFormat, int buffer);

    @JSBody(params = {"target", "index", "buffer", "offset", "size"}, script = """
            const state=window.__gaiusGL;
            const bufferSize=state.bufferSizes.get(buffer)||Number(size);
            const available=Math.max(0,bufferSize-Number(offset));
            const range=target===0x8A11 && available>Number(size)
              ? Math.min(available,Math.max(Number(size),256))
              : Number(size);
            const key=(target|0)*65536+(index|0);
            let previous=state.indexedBufferBindings.get(key);
            if (previous && previous.range===true
                && (previous.buffer|0)===(buffer|0)
                && Number(previous.offset)===Number(offset)
                && Number(previous.size)===range) return;
            window.__gaiusWebGL.bindBufferRange(
              target,index,buffer===0?null:state.buffers.get(buffer),Number(offset),range);
            if (!previous) {
              previous={};
              state.indexedBufferBindings.set(key,previous);
            }
            previous.range=true;
            previous.buffer=buffer|0;
            previous.offset=Number(offset);
            previous.size=range;
            """)
    private static native void bindBufferRangeJs(
            int target, int index, int buffer, int offset, int size);

    public static void bindBufferRange(
            int target, int index, int buffer, long offset, long size) {
        bindBufferRangeJs(target, index, buffer, (int) offset, (int) size);
    }

    @JSBody(params = {"target", "index", "buffer"}, script = """
            const state=window.__gaiusGL;
            const key=(target|0)*65536+(index|0);
            let previous=state.indexedBufferBindings.get(key);
            if (previous && previous.range===false
                && (previous.buffer|0)===(buffer|0)) return;
            window.__gaiusWebGL.bindBufferBase(
              target,index,buffer===0?null:state.buffers.get(buffer));
            if (!previous) {
              previous={};
              state.indexedBufferBindings.set(key,previous);
            }
            previous.range=false;
            previous.buffer=buffer|0;
            """)
    public static native void bindBufferBase(int target, int index, int buffer);

    @JSBody(params = {"sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (sourceTarget===gl.ELEMENT_ARRAY_BUFFER || targetTarget===gl.ELEMENT_ARRAY_BUFFER) {
              state.ensureLogicalElementBuffer(state.getVaoEmu());
            }
            gl.copyBufferSubData(
              sourceTarget,targetTarget,Number(sourceOffset),Number(targetOffset),Number(size));
            const sourceBuffer=state.boundBuffers.get(sourceTarget)|0;
            const targetBuffer=state.boundBuffers.get(targetTarget)|0;
            const source=state.bufferBytes.get(sourceBuffer);
            if (targetBuffer) {
              if (source && state.shouldShadowBufferTarget(targetTarget,targetBuffer)) {
                const start=Number(sourceOffset);
                const end=start+Number(size);
                state.shadowBufferSubData(targetBuffer,Number(targetOffset),source.subarray(start,end));
              } else {
                if (!state.dropBufferShadow(
                    targetBuffer,source ? 'copy-target:'+targetTarget : 'copy-missing-source')) {
                  state.bumpBufferVersion(targetBuffer);
                }
              }
            }
            """)
    public static native void copyBufferSubData(
            int sourceTarget, int targetTarget, long sourceOffset, long targetOffset, long size);

    @JSBody(params = {"sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousReadId=state.boundBuffers.get(gl.COPY_READ_BUFFER)|0;
            const previousWriteId=state.boundBuffers.get(gl.COPY_WRITE_BUFFER)|0;
            const previousRead=previousReadId ? state.buffers.get(previousReadId) : null;
            const previousWrite=previousWriteId ? state.buffers.get(previousWriteId) : null;
            const readBindingMatches=previousReadId===(sourceBuffer|0);
            const writeBindingMatches=previousWriteId===(targetBuffer|0);
            if (!readBindingMatches) gl.bindBuffer(gl.COPY_READ_BUFFER,state.buffers.get(sourceBuffer));
            if (!writeBindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(targetBuffer));
            gl.copyBufferSubData(
              gl.COPY_READ_BUFFER,gl.COPY_WRITE_BUFFER,Number(sourceOffset),Number(targetOffset),Number(size));
            if (targetBuffer) {
              const end=Number(targetOffset)+Number(size);
              const known=state.bufferSizes.get(targetBuffer)||0;
              if (end > known) state.bufferSizes.set(targetBuffer,end);
              const source=state.bufferBytes.get(sourceBuffer);
              if (source && state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(targetBuffer|0)) {
                const start=Number(sourceOffset);
                state.shadowBufferSubData(targetBuffer,Number(targetOffset),source.subarray(start,start+Number(size)));
              } else {
                if (!state.dropBufferShadow(
                    targetBuffer,source ? 'named-copy-target' : 'named-copy-missing-source')) {
                  state.bumpBufferVersion(targetBuffer);
                }
              }
            }
            if (!readBindingMatches) gl.bindBuffer(gl.COPY_READ_BUFFER,previousRead);
            if (!writeBindingMatches) gl.bindBuffer(gl.COPY_WRITE_BUFFER,previousWrite);
            const physical=(readBindingMatches?0:2)+(writeBindingMatches?0:2);
            state.noteNamedBufferBindings(physical,4-physical);
            """)
    public static native void copyNamedBufferSubData(
            int sourceBuffer, int targetBuffer, long sourceOffset, long targetOffset, long size);

    public static int getUniformBlockIndex(int program, CharSequence name) {
        return getUniformBlockIndexJs(program, name.toString());
    }

    @JSBody(params = {"program", "name"}, script = """
            const gl=window.__gaiusWebGL;
            const index=gl.getUniformBlockIndex(window.__gaiusGL.programs.get(program),name);
            return index===gl.INVALID_INDEX ? -1 : index|0;
            """)
    private static native int getUniformBlockIndexJs(int program, String name);

    @JSBody(params = {"program", "index"}, script = """
            return window.__gaiusWebGL.getActiveUniformBlockName(
              window.__gaiusGL.programs.get(program),index)||'';
            """)
    public static native String getActiveUniformBlockName(int program, int index);

    @JSBody(params = {"program", "block", "binding"}, script = """
            window.__gaiusWebGL.uniformBlockBinding(
              window.__gaiusGL.programs.get(program),block,binding);
            """)
    public static native void uniformBlockBinding(int program, int block, int binding);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.samplers.set(id,window.__gaiusWebGL.createSampler()); return id|0;
            """)
    public static native int genSampler();

    @JSBody(params = {"unit", "sampler"}, script = """
            const state=window.__gaiusGL;
            if (state.samplerBindings.has(unit|0)
                && (state.samplerBindings.get(unit|0)|0)===(sampler|0)) return;
            window.__gaiusWebGL.bindSampler(
              unit,sampler===0?null:state.samplers.get(sampler));
            state.samplerBindings.set(unit|0,sampler|0);
            """)
    public static native void bindSampler(int unit, int sampler);

    @JSBody(params = {"sampler"}, script = """
            const state=window.__gaiusGL, object=state.samplers.get(sampler);
            if (object) window.__gaiusWebGL.deleteSampler(object); state.samplers.delete(sampler);
            state.samplerBindings.forEach(function(bound,unit) {
              if ((bound|0)===(sampler|0)) state.samplerBindings.delete(unit|0);
            });
            """)
    public static native void deleteSampler(int sampler);

    @JSBody(params = {"sampler", "parameter", "value"}, script = """
            window.__gaiusWebGL.samplerParameteri(
              window.__gaiusGL.samplers.get(sampler),parameter,value);
            """)
    public static native void samplerParameteri(int sampler, int parameter, int value);

    @JSBody(params = {"sampler", "parameter", "value"}, script = """
            window.__gaiusWebGL.samplerParameterf(
              window.__gaiusGL.samplers.get(sampler),parameter,value);
            """)
    public static native void samplerParameterf(int sampler, int parameter, float value);

    @JSBody(script = """
            const state=window.__gaiusGL;
            if (state) state.gpuNextFenceRetireOwned=true;
            """)
    public static native void markNextFenceRetireOwned();

    @JSBody(params = {"condition", "flags"}, script = """
            const state=window.__gaiusGL,gl=window.__gaiusWebGL;
            const retireOwned=!!(state && state.gpuNextFenceRetireOwned);
            if (state) state.gpuNextFenceRetireOwned=false;
            if (!state || !gl) return 0;
            if (gl.isContextLost && gl.isContextLost()) {
              if (state.gpuMarkContextLost) state.gpuMarkContextLost('fence-create');
              if (state.gpuRecordFenceCreateFailure) {
                state.gpuRecordFenceCreateFailure('context-lost');
              }
              return 0;
            }
            let object=null;
            try {
              object=gl.fenceSync(condition,flags);
            } catch (error) {
              if (state.gpuRecordFenceCreateFailure) {
                state.gpuRecordFenceCreateFailure(error && (error.message||error.name)||error);
              }
              return 0;
            }
            if (!object) {
              if (state.gpuRecordFenceCreateFailure) {
                state.gpuRecordFenceCreateFailure('fenceSync-returned-null');
              }
              return 0;
            }
            const id=state.next++;
            state.syncs.set(id,object);
            if (state.gpuRecordFenceCreated) {
              state.gpuRecordFenceCreated(id|0,retireOwned);
            }
            return id|0;
            """)
    private static native int fenceSyncJs(int condition, int flags);

    public static long fenceSync(int condition, int flags) {
        return fenceSyncJs(condition, flags);
    }

    @JSBody(params = {"sync", "flags", "timeout"}, script = """
            const state=window.__gaiusGL,gl=window.__gaiusWebGL,id=sync|0;
            const requested=Math.max(0,Number(timeout)||0);
            if (!state || !gl) return 0x911D;
            const maxTimeout=Math.max(0,Number(state.gpuMaxClientWaitTimeout)||0);
            const contextLost=!!(gl.isContextLost && gl.isContextLost());
            if (contextLost && state.gpuMarkContextLost) {
              state.gpuMarkContextLost('client-wait');
            }
            const object=state.syncs.get(id);
            if (!object || contextLost) {
              if (state.gpuRecordFenceWait) {
                state.gpuRecordFenceWait(
                  id,0x911D,requested,maxTimeout,contextLost,
                  object ? 'context-lost' : 'missing-sync');
              }
              return 0x911D;
            }
            const safeFlags=(flags|0)&1;
            let status=0x911D;
            let reason='wait-failed';
            try {
              // WebGL permits a zero maximum and only advances new syncs between event-loop tasks.
              // Always poll here; submit retries after the browser's normal frame yield.
              status=gl.clientWaitSync(object,safeFlags,0)|0;
              reason=status===0x911D ? 'webgl-wait-failed' : '';
            } catch (error) {
              reason=String(error && (error.message||error.name)||error);
              status=0x911D;
            }
            if (status!==0x911A && status!==0x911B
                && status!==0x911C && status!==0x911D) {
              reason='unexpected-status-'+status;
              status=0x911D;
            }
            const lostAfter=!!(gl.isContextLost && gl.isContextLost());
            if (lostAfter && state.gpuMarkContextLost) {
              state.gpuMarkContextLost('client-wait-result');
            }
            if (state.gpuRecordFenceWait) {
              state.gpuRecordFenceWait(id,status,requested,maxTimeout,lostAfter,reason);
            }
            return status|0;
            """)
    private static native int clientWaitSyncJs(int sync, int flags, int timeout);

    public static int clientWaitSync(long sync, int flags, long timeout) {
        int requestedTimeout = timeout <= 0L
                ? 0
                : (int) Math.min(timeout, Integer.MAX_VALUE);
        return clientWaitSyncJs((int) sync, flags & 1, requestedTimeout);
    }

    @JSBody(params = {"sync"}, script = """
            const state=window.__gaiusGL, id=sync|0;
            if (!state || !window.__gaiusWebGL) return;
            const object=state.syncs.get(id);
            if (!object) {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.gpuFenceDuplicateDeletes=(stats.gpuFenceDuplicateDeletes||0)+1;
              return;
            }
            try {
              window.__gaiusWebGL.deleteSync(object);
            } catch (error) {
              const failureStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              failureStats.gpuFenceDeleteFailures=
                (failureStats.gpuFenceDeleteFailures||0)+1;
              failureStats.gpuFenceDeleteFailureReason=String(
                error && (error.message||error.name)||error);
              if (window.__gaiusWebGL.isContextLost
                  && window.__gaiusWebGL.isContextLost()
                  && state.gpuMarkContextLost) {
                state.gpuMarkContextLost('fence-delete');
              }
            }
            state.syncs.delete(id);
            if (state.gpuRecordFenceDeleted) state.gpuRecordFenceDeleted(id);
            """)
    private static native void deleteSyncJs(int sync);

    public static void deleteSync(long sync) {
        deleteSyncJs((int) sync);
    }

    public static void reportWorldSelectionList(Object listObject) {
        try {
            if (!(listObject instanceof WorldSelectionList list)) {
                return;
            }
            List<?> children = list.children();
            int count = children.size();
            String firstName = null;
            int firstX = -1;
            int firstY = -1;
            int firstWidth = -1;
            int firstHeight = -1;
            boolean firstCanInteract = false;
            if (count > 0 && children.get(0) instanceof WorldListEntry first) {
                firstName = first.getLevelName();
                firstX = first.getContentX();
                firstY = first.getContentY();
                firstWidth = first.getContentWidth();
                firstHeight = first.getContentHeight();
                firstCanInteract = first.canInteract();
            }
            String selectedName = null;
            boolean selectedCanInteract = false;
            if (list.getSelected() instanceof WorldListEntry selected) {
                selectedName = selected.getLevelName();
                selectedCanInteract = selected.canInteract();
            }
            reportWorldSelectionListJs(
                    count, list.getRowLeft(), list.getRowRight(), list.getRowWidth(),
                    count > 0 ? list.getRowTop(0) : -1,
                    firstName, firstX, firstY, firstWidth, firstHeight, firstCanInteract,
                    selectedName, selectedCanInteract);
        } catch (Throwable ignored) {
            // Telemetry must never break rendering.
        }
    }

    @JSBody(params = {
            "count", "rowLeft", "rowRight", "rowWidth", "rowTop",
            "firstName", "firstX", "firstY", "firstWidth", "firstHeight", "firstCanInteract",
            "selectedName", "selectedCanInteract"
    }, script = """
            window.__gaiusWorldSelection = {
              "count": count,
              "rowLeft": rowLeft,
              "rowRight": rowRight,
              "rowWidth": rowWidth,
              "rowTop": rowTop,
              "first": firstName !== null ? {
                "name": firstName,
                "x": firstX,
                "y": firstY,
                "width": firstWidth,
                "height": firstHeight,
                "canInteract": firstCanInteract
              } : null,
              "selected": selectedName !== null ? {
                "name": selectedName,
                "canInteract": selectedCanInteract
              } : null,
              "at": Date.now()
            };
            """)
    private static native void reportWorldSelectionListJs(
            int count, int rowLeft, int rowRight, int rowWidth, int rowTop,
            String firstName, int firstX, int firstY, int firstWidth, int firstHeight, boolean firstCanInteract,
            String selectedName, boolean selectedCanInteract);

    @JSBody(script = """
            const now=(typeof performance !== 'undefined' && performance.now)
              ? performance.now() : Date.now();
            const current=window.__gaiusMinecraftState || null;
            const interval=current && current.level ? 100 : 50;
            const previous=Number(window.__gaiusMinecraftStateReportedAt) || 0;
            if (previous && now-previous < interval) return false;
            window.__gaiusMinecraftStateReportedAt=now;
            return true;
            """)
    public static native boolean shouldReportMinecraftState();

    public static void reportMinecraftState(
            Object screen, Object overlay, Object level, Object player, Object gameMode, Object hitResult,
            boolean noRender, boolean running, boolean pause) {
        String screenTitle = null;
        int screenWidth = -1;
        int screenHeight = -1;
        String screenWidgetsJson = "[]";
        double playerX = Double.NaN;
        double playerY = Double.NaN;
        double playerZ = Double.NaN;
        float playerYaw = Float.NaN;
        float playerPitch = Float.NaN;
        int loadedChunkCount = -1;
        boolean playerCollisionKnown = false;
        boolean playerCollisionFree = false;
        int selectedSlot = -1;
        String selectedItem = null;
        int selectedCount = 0;
        String playerMode = null;
        String hitClass = className(hitResult);
        String hitType = null;
        String hitBlockPos = null;
        String hitDirection = null;
        String hitBlockState = null;
        String hitEntity = null;
        if (screen instanceof Screen typedScreen) {
            try {
                screenWidth = typedScreen.width;
                screenHeight = typedScreen.height;
                screenTitle = typedScreen.getTitle() == null ? null : typedScreen.getTitle().getString();
                screenWidgetsJson = describeScreenWidgets(typedScreen);
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        if (player instanceof Entity entity) {
            try {
                playerX = entity.getX();
                playerY = entity.getY();
                playerZ = entity.getZ();
                playerYaw = entity.getYRot();
                playerPitch = entity.getXRot();
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        if (player instanceof net.minecraft.world.entity.player.Player typedPlayer) {
            try {
                Inventory inventory = typedPlayer.getInventory();
                selectedSlot = inventory.getSelectedSlot();
                ItemStack stack = inventory.getSelectedItem();
                if (stack != null && !stack.isEmpty()) {
                    selectedItem = String.valueOf(stack.getItem());
                    selectedCount = stack.getCount();
                }
                playerMode = String.valueOf(typedPlayer.gameMode());
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        if (gameMode instanceof MultiPlayerGameMode typedGameMode) {
            try {
                playerMode = String.valueOf(typedGameMode.getPlayerMode());
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        if (level instanceof ClientLevel clientLevel) {
            try {
                loadedChunkCount = clientLevel.getChunkSource().getLoadedChunksCount();
                if (player instanceof Entity entity) {
                    playerCollisionFree = clientLevel.noCollision(entity);
                    playerCollisionKnown = true;
                }
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        if (hitResult instanceof HitResult typedHit) {
            try {
                hitType = String.valueOf(typedHit.getType());
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        if (hitResult instanceof BlockHitResult blockHit) {
            try {
                BlockPos pos = blockHit.getBlockPos();
                hitBlockPos = String.valueOf(pos);
                hitDirection = String.valueOf(blockHit.getDirection());
                if (level instanceof ClientLevel clientLevel) {
                    BlockState state = clientLevel.getBlockState(pos);
                    hitBlockState = String.valueOf(state);
                }
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        } else if (hitResult instanceof EntityHitResult entityHit) {
            try {
                hitEntity = String.valueOf(entityHit.getEntity());
            } catch (Throwable ignored) {
                // Telemetry must never break the game loop.
            }
        }
        reportMinecraftStateJs(
                className(screen), screenTitle, screenWidth, screenHeight, screenWidgetsJson,
                className(overlay), className(level), className(player),
                playerX, playerY, playerZ, playerYaw, playerPitch,
                loadedChunkCount, playerCollisionKnown, playerCollisionFree,
                playerMode, selectedSlot, selectedItem, selectedCount,
                hitClass, hitType, hitBlockPos, hitDirection, hitBlockState, hitEntity,
                noRender, running, pause);
    }

    public static Object fallbackClientLevel(Object minecraft, Object level) {
        if (level != null) {
            return level;
        }
        if (!(minecraft instanceof Minecraft typedMinecraft)) {
            return null;
        }
        try {
            ClientPacketListener connection = typedMinecraft.getConnection();
            if (connection == null) {
                return null;
            }
            ClientLevel connectionLevel = connection.getLevel();
            return connectionLevel == null ? null : connectionLevel;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeScreenWidgets(Screen screen) {
        StringBuilder builder = new StringBuilder(2048);
        builder.append('[');
        int count = 0;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) {
                continue;
            }
            if (count >= 80) {
                break;
            }
            if (count > 0) {
                builder.append(',');
            }
            builder.append('{');
            jsonField(builder, "type", widget.getClass().getName()).append(',');
            jsonField(builder, "text", widget.getMessage() == null ? null : widget.getMessage().getString()).append(',');
            jsonField(builder, "x", widget.getX()).append(',');
            jsonField(builder, "y", widget.getY()).append(',');
            jsonField(builder, "width", widget.getWidth()).append(',');
            jsonField(builder, "height", widget.getHeight()).append(',');
            jsonField(builder, "active", widget.active).append(',');
            jsonField(builder, "visible", widget.visible).append(',');
            jsonField(builder, "focused", widget.isFocused());
            builder.append('}');
            count++;
        }
        builder.append(']');
        return builder.toString();
    }

    private static StringBuilder jsonField(StringBuilder builder, String name, String value) {
        jsonString(builder, name);
        builder.append(':');
        if (value == null) {
            builder.append("null");
        } else {
            jsonString(builder, value);
        }
        return builder;
    }

    private static StringBuilder jsonField(StringBuilder builder, String name, int value) {
        jsonString(builder, name);
        builder.append(':').append(value);
        return builder;
    }

    private static StringBuilder jsonField(StringBuilder builder, String name, boolean value) {
        jsonString(builder, name);
        builder.append(':').append(value ? "true" : "false");
        return builder;
    }

    private static void jsonString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    @JSBody(params = {
            "screen", "screenTitle", "screenWidth", "screenHeight", "screenWidgetsJson",
            "overlay", "level", "playerClass",
            "playerX", "playerY", "playerZ", "playerYaw", "playerPitch",
            "loadedChunkCount", "playerCollisionKnown", "playerCollisionFree",
            "playerMode", "selectedSlot", "selectedItem", "selectedCount",
            "hitClass", "hitType", "hitBlockPos", "hitDirection", "hitBlockState", "hitEntity",
            "noRender", "running", "pause"
    }, script = """
            var playerState = null;
            if (playerClass !== null) {
              playerState = {
                "className": playerClass,
                "x": Number.isFinite(playerX) ? playerX : null,
                "y": Number.isFinite(playerY) ? playerY : null,
                "z": Number.isFinite(playerZ) ? playerZ : null,
                "yaw": Number.isFinite(playerYaw) ? playerYaw : null,
                "pitch": Number.isFinite(playerPitch) ? playerPitch : null,
                "collisionFree": playerCollisionKnown ? !!playerCollisionFree : null,
                "gameMode": playerMode,
                "selectedItem": selectedSlot >= 0 ? {
                  "slot": selectedSlot,
                  "item": selectedItem,
                  "count": selectedCount
                } : null
              };
            }
            var hitState = null;
            if (hitClass !== null || hitType !== null) {
              hitState = {
                "className": hitClass,
                "type": hitType,
                "blockPos": hitBlockPos,
                "direction": hitDirection,
                "blockState": hitBlockState,
                "entity": hitEntity
              };
            }
            var screenWidgetState = [];
            if (screenWidgetsJson) {
              try {
                screenWidgetState = JSON.parse(screenWidgetsJson);
              } catch (ignored) {
                screenWidgetState = [{"type":"<parse-error>","text":String(screenWidgetsJson).slice(0,160)}];
              }
            }
            window.__gaiusMinecraftState = {
              "screen": screen,
              "screenTitle": screenTitle,
              "screenSize": screenWidth >= 0 && screenHeight >= 0 ? {
                "width": screenWidth,
                "height": screenHeight
              } : null,
              "screenWidgets": screenWidgetState,
              "overlay": overlay,
              "level": level,
              "loadedChunkCount": loadedChunkCount >= 0 ? loadedChunkCount : null,
              "player": playerState,
              "gameMode": playerMode,
              "hit": hitState,
              "worldSelection": window.__gaiusWorldSelection || null,
              "noRender": noRender,
              "running": running,
              "pause": pause,
              "at": Date.now()
            };
            """)
    private static native void reportMinecraftStateJs(
            String screen, String screenTitle, int screenWidth, int screenHeight, String screenWidgetsJson,
            String overlay, String level, String playerClass,
            double playerX, double playerY, double playerZ, float playerYaw, float playerPitch,
            int loadedChunkCount, boolean playerCollisionKnown, boolean playerCollisionFree,
            String playerMode, int selectedSlot, String selectedItem, int selectedCount,
            String hitClass, String hitType, String hitBlockPos, String hitDirection, String hitBlockState,
            String hitEntity,
            boolean noRender, boolean running, boolean pause);

    @JSBody(params = {"type"}, script = """
            const counters = window.__gaiusMinecraftCounters || (window.__gaiusMinecraftCounters = {});
            const gui = counters.guiCurrentSubmits || (counters.guiCurrentSubmits = {
              total: 0,
              element: 0,
              text: 0,
              item: 0,
              pip: 0,
              blitLayer: 0,
              glyphLayer: 0
            });
            const key = String(type || "unknown");
            gui[key] = (gui[key] || 0) + 1;
            gui.total = (gui.total || 0) + 1;
            counters.guiSubmitLastType = key;
            counters.guiSubmitLastAt = Date.now();
            """)
    public static native void reportGuiSubmit(String type);

    @JSBody(script = """
            const counters = window.__gaiusMinecraftCounters || (window.__gaiusMinecraftCounters = {});
            counters.guiRenderFrames = (counters.guiRenderFrames || 0) + 1;
            counters.guiLastDrawCalls = 0;
            counters.guiLastDrawIndices = 0;
            counters.guiLastBaseVertexNonZero = 0;
            if (counters.guiItemAtlasTelemetryEnabled === undefined) {
              let enabled = false;
              try {
                const params = new URLSearchParams(window.location.search || '');
                enabled = params.get('atlasDiag') === '1'
                    || params.get('guiDiag') === '1'
                    || params.get('diag') === 'gui';
              } catch (ignored) {
                enabled = false;
              }
              counters.guiItemAtlasTelemetryEnabled = !!enabled;
            }
            if (counters.guiItemAtlasTelemetryEnabled) {
              counters.guiItemAtlasLast = Object.assign({}, counters.guiItemAtlasCurrent || {});
              counters.guiItemAtlasCurrent = {
                requests: 0,
                hits: 0,
                hitStatic: 0,
                hitAnimated: 0,
                renders: 0,
                renderMiss: 0,
                renderAnimatedRefresh: 0,
                renderAnimatedNoPosition: 0,
                oversized: 0,
                invalidations: 0
              };
            }
            counters.guiRenderStartedAt = Date.now();
            """)
    public static native void reportGuiRenderStart();

    @JSBody(params = {"drawCount", "meshCount", "firstDrawIndexAfterBlur"}, script = """
            const counters = window.__gaiusMinecraftCounters || (window.__gaiusMinecraftCounters = {});
            const plan = counters.guiLastDrawPlan || (counters.guiLastDrawPlan = {});
            plan.drawCount = drawCount | 0;
            plan.meshCount = meshCount | 0;
            plan.firstDrawIndexAfterBlur = firstDrawIndexAfterBlur | 0;
            plan.at = Date.now();
            const state = window.__gaiusGL;
            if (state) {
              state.restoreGuiItemOffscreenScissor('gui-draw-plan');
              if (state.guiCullFaceBatchActive && state.enabledCaps && state.enabledCaps.has(window.__gaiusWebGL.CULL_FACE)) {
                window.__gaiusWebGL.enable(window.__gaiusWebGL.CULL_FACE);
                state.guiCullFaceBatchActive = false;
                var stats = window.__gaiusGLStats || (window.__gaiusGLStats = {});
                stats.guiCullFaceBatchForcedRestores = (stats.guiCullFaceBatchForcedRestores || 0) + 1;
              }
              let enabled = false;
              try {
                const params = new URLSearchParams(window.location.search || '');
                enabled = params.get('guiDiag') === '1' || params.get('diag') === 'gui';
              } catch (ignored) {
                enabled = false;
              }
              state.guiDrawDiagnostics = !!enabled;
              state.guiDrawsRemaining = Math.max(0, drawCount | 0);
            }
            """)
    public static native void reportGuiDrawPlan(int drawCount, int meshCount, int firstDrawIndexAfterBlur);

    @JSBody(params = {"indexCount", "baseVertex"}, script = """
            const counters = window.__gaiusMinecraftCounters || (window.__gaiusMinecraftCounters = {});
            counters.guiLastDrawCalls = (counters.guiLastDrawCalls || 0) + 1;
            counters.guiLastDrawIndices = (counters.guiLastDrawIndices || 0) + (indexCount | 0);
            if ((baseVertex | 0) !== 0) {
              counters.guiLastBaseVertexNonZero = (counters.guiLastBaseVertexNonZero || 0) + 1;
            }
            counters.guiLastDrawCall = {
              indexCount: indexCount | 0,
              baseVertex: baseVertex | 0,
              at: Date.now()
            };
            """)
    public static native void reportGuiDrawCall(int indexCount, int baseVertex);

    @JSBody(params = {"animated"}, script = """
            const counters = window.__gaiusMinecraftCounters;
            if (!counters || !counters.guiItemAtlasTelemetryEnabled) return;
            const atlas = counters.guiItemAtlasCurrent || (counters.guiItemAtlasCurrent = {
              requests: 0,
              hits: 0,
              hitStatic: 0,
              hitAnimated: 0,
              renders: 0,
              renderMiss: 0,
              renderAnimatedRefresh: 0,
              renderAnimatedNoPosition: 0,
              oversized: 0,
              invalidations: 0
            });
            atlas.requests = (atlas.requests || 0) + 1;
            atlas.hits = (atlas.hits || 0) + 1;
            if (animated) {
              atlas.hitAnimated = (atlas.hitAnimated || 0) + 1;
            } else {
              atlas.hitStatic = (atlas.hitStatic || 0) + 1;
            }
            counters.guiItemAtlasLastEvent = {
              type: "hit",
              animated: !!animated,
              at: Date.now()
            };
            """)
    public static native void reportGuiItemAtlasHit(boolean animated);

    @JSBody(params = {"animated", "hadPosition"}, script = """
            const counters = window.__gaiusMinecraftCounters;
            if (!counters || !counters.guiItemAtlasTelemetryEnabled) return;
            const atlas = counters.guiItemAtlasCurrent || (counters.guiItemAtlasCurrent = {
              requests: 0,
              hits: 0,
              hitStatic: 0,
              hitAnimated: 0,
              renders: 0,
              renderMiss: 0,
              renderAnimatedRefresh: 0,
              renderAnimatedNoPosition: 0,
              oversized: 0,
              invalidations: 0
            });
            atlas.requests = (atlas.requests || 0) + 1;
            atlas.renders = (atlas.renders || 0) + 1;
            if (animated && hadPosition) {
              atlas.renderAnimatedRefresh = (atlas.renderAnimatedRefresh || 0) + 1;
            } else {
              atlas.renderMiss = (atlas.renderMiss || 0) + 1;
              if (animated) {
                atlas.renderAnimatedNoPosition = (atlas.renderAnimatedNoPosition || 0) + 1;
              }
            }
            counters.guiItemAtlasLastEvent = {
              type: "render",
              animated: !!animated,
              hadPosition: !!hadPosition,
              at: Date.now()
            };
            """)
    public static native void reportGuiItemAtlasRender(boolean animated, boolean hadPosition);

    @JSBody(script = """
            const counters = window.__gaiusMinecraftCounters;
            if (!counters || !counters.guiItemAtlasTelemetryEnabled) return;
            const atlas = counters.guiItemAtlasCurrent || (counters.guiItemAtlasCurrent = {
              requests: 0,
              hits: 0,
              hitStatic: 0,
              hitAnimated: 0,
              renders: 0,
              renderMiss: 0,
              renderAnimatedRefresh: 0,
              renderAnimatedNoPosition: 0,
              oversized: 0,
              invalidations: 0
            });
            atlas.requests = (atlas.requests || 0) + 1;
            atlas.oversized = (atlas.oversized || 0) + 1;
            counters.guiItemAtlasLastEvent = {
              type: "oversized",
              at: Date.now()
            };
            """)
    public static native void reportGuiItemAtlasOversized();

    @JSBody(params = {"previousPositions"}, script = """
            const counters = window.__gaiusMinecraftCounters;
            if (!counters || !counters.guiItemAtlasTelemetryEnabled) return;
            const atlas = counters.guiItemAtlasCurrent || (counters.guiItemAtlasCurrent = {
              requests: 0,
              hits: 0,
              hitStatic: 0,
              hitAnimated: 0,
              renders: 0,
              renderMiss: 0,
              renderAnimatedRefresh: 0,
              renderAnimatedNoPosition: 0,
              oversized: 0,
              invalidations: 0
            });
            atlas.invalidations = (atlas.invalidations || 0) + 1;
            counters.guiItemAtlasInvalidations = (counters.guiItemAtlasInvalidations || 0) + 1;
            counters.guiItemAtlasInvalidationLast = {
              previousPositions: previousPositions | 0,
              at: Date.now()
            };
            """)
    public static native void reportGuiItemAtlasInvalidated(int previousPositions);

    public static void reportMinecraftEvent(String event) {
        reportMinecraftEventJs(event, null);
    }

    public static void reportMinecraftEvent(String event, String detail) {
        reportMinecraftEventJs(event, detail);
    }

    public static void reportMinecraftThrowable(String phase, Throwable throwable) {
        String detail = describeThrowable(throwable);
        reportMinecraftEvent("throwable." + phase, detail);
        reportMinecraftThrowableJs(phase, throwable, detail);
    }

    public static boolean shouldSkipWorldRenderForScreen(Screen screen) {
        if (screen == null) {
            inventoryWorldRenderFrame = 0;
            inventoryWorldRenderScreen = null;
            return false;
        }
        String screenName = screen.getClass().getName();
        if (!screenName.startsWith("net.minecraft.client.gui.screens.inventory.")) {
            inventoryWorldRenderFrame = 0;
            inventoryWorldRenderScreen = null;
            return false;
        }
        if (!screenName.equals(inventoryWorldRenderScreen)) {
            inventoryWorldRenderScreen = screenName;
            inventoryWorldRenderFrame = 0;
            return false;
        }
        int frame = ++inventoryWorldRenderFrame;
        return frame > 1;
    }

    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "<null throwable>";
        }
        StringBuilder builder = new StringBuilder(2048);
        appendThrowable(builder, throwable, "");
        return builder.toString();
    }

    private static void appendThrowable(StringBuilder builder, Throwable throwable, String prefix) {
        builder.append(prefix)
                .append(throwable.getClass().getName())
                .append(": ")
                .append(throwable.getMessage())
                .append('\n');
        StackTraceElement[] stack = throwable.getStackTrace();
        int limit = Math.min(stack.length, 64);
        for (int i = 0; i < limit; i++) {
            builder.append(prefix).append("  at ").append(stack[i]).append('\n');
        }
        if (stack.length > limit) {
            builder.append(prefix).append("  ... ").append(stack.length - limit).append(" more\n");
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            builder.append(prefix).append("Suppressed: ");
            appendThrowable(builder, suppressed, prefix + "  ");
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            builder.append(prefix).append("Caused by: ");
            appendThrowable(builder, cause, prefix + "  ");
        }
    }

    @JSBody(params = {"phase", "throwable", "detail"}, script = """
            const parts = [detail];
            const append = (label, value) => {
              if (value === null || value === undefined) return;
              try {
                parts.push(label + ': ' + String(value));
              } catch (e) {
                parts.push(label + ': <toString failed>');
              }
            };
            try {
              append('jsException', throwable && throwable.$jsException);
              append('jsException.stack', throwable && throwable.$jsException && throwable.$jsException.stack);
              append('jsException.message', throwable && throwable.$jsException && throwable.$jsException.message);
              append('jsStack', throwable && throwable.stack);
              append('jsMessage', throwable && throwable.message);
              if (throwable) {
                append('ownKeys', Object.keys(throwable).slice(0, 40).join(','));
              }
            } catch (e) {
              append('diagnosticError', e);
            }
            const fullDetail = parts.join('\\n');
            if (typeof console !== 'undefined' && console.error) {
              console.error('[GAIUS_THROWABLE] ' + phase + '\\n' + fullDetail);
            }
            """)
    private static native void reportMinecraftThrowableJs(String phase, Throwable throwable, String detail);

    @JSBody(params = {"event", "detail"}, script = """
            var counters = globalThis.__gaiusMinecraftCounters;
            if (!counters) {
              counters = {};
              globalThis.__gaiusMinecraftCounters = counters;
            }
            const key = detail == null ? event : event + ":" + detail;
            const count = (counters[key] || 0) + 1;
            counters[key] = count;
            var events = globalThis.__gaiusMinecraftEvents;
            if (!events) {
              events = [];
              globalThis.__gaiusMinecraftEvents = events;
            }
            if (count <= 12 || count % 100 === 0 || /ready|loaded|loadingPacketsReceived/.test(event)) {
              events.push({"event": event, "detail": detail, "count": count, "at": Date.now()});
              if (events.length > 500) events.splice(0, events.length - 500);
            }
            """)
    private static native void reportMinecraftEventJs(String event, String detail);

    private static String className(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ClientLevel) {
            return "net.minecraft.client.multiplayer.ClientLevel";
        }
        if (value instanceof LocalPlayer) {
            return "net.minecraft.client.player.LocalPlayer";
        }
        if (value instanceof MultiPlayerGameMode) {
            return "net.minecraft.client.multiplayer.MultiPlayerGameMode";
        }
        try {
            return value.getClass().getName();
        } catch (Throwable ignored) {
            try {
                return String.valueOf(value);
            } catch (Throwable ignoredAgain) {
                return "<class-name-unavailable>";
            }
        }
    }

    private static Int8Array bytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        return Int8Array.fromJavaBuffer(buffer);
    }

    private static Int8Array allBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        copy.clear();
        return Int8Array.fromJavaBuffer(copy);
    }

    private static Int8Array bytesSlice(ByteBuffer buffer, long offset, long length) {
        if (offset < 0L || length < 0L || length > Integer.MAX_VALUE
                || offset > buffer.capacity() - length) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer flush range: " + offset + " + " + length);
        }
        ByteBuffer copy = buffer.duplicate();
        copy.position((int) offset);
        copy.limit((int) (offset + length));
        return Int8Array.fromJavaBuffer(copy);
    }

    private static Int8Array pointerBytes(long address, int length) {
        if (address == 0L) {
            return null;
        }
        if (length <= 0) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        ByteBuffer bytes = MemoryUtil.memByteBuffer(address, length);
        return Int8Array.fromJavaBuffer(bytes);
    }

    private static Float32Array floats(FloatBuffer buffer) {
        int length = buffer.remaining();
        float[] data = UNIFORM_SCRATCH.get().floats(length);
        int position = buffer.position();
        for (int i = 0; i < length; i++) {
            data[i] = buffer.get(position + i);
        }
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array ints(IntBuffer buffer) {
        int length = buffer.remaining();
        int[] data = UNIFORM_SCRATCH.get().ints(length);
        int position = buffer.position();
        for (int i = 0; i < length; i++) {
            data[i] = buffer.get(position + i);
        }
        return Int32Array.fromJavaArray(data);
    }

    private static Float32Array pointerFloats(long address, int count) {
        if (address == 0L) {
            return null;
        }
        if (count <= 0) {
            return Float32Array.fromJavaArray(UNIFORM_SCRATCH.get().floats(0));
        }
        FloatBuffer floats = MemoryUtil.memByteBuffer(address, count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] data = UNIFORM_SCRATCH.get().floats(count);
        floats.get(data);
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array pointerInts(long address, int count) {
        if (address == 0L) {
            return null;
        }
        if (count <= 0) {
            return Int32Array.fromJavaArray(UNIFORM_SCRATCH.get().ints(0));
        }
        IntBuffer ints = MemoryUtil.memByteBuffer(address, count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        int[] data = UNIFORM_SCRATCH.get().ints(count);
        ints.get(data);
        return Int32Array.fromJavaArray(data);
    }

    private static int textureUploadLength(int width, int height, int format, int type) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int bytesPerPixel = bytesPerPixel(format, type);
        int rowLength = unpackRowLength > 0 ? unpackRowLength : width;
        long rowStride = aligned((long) rowLength * bytesPerPixel, unpackAlignment);
        long length = (long) unpackSkipRows * rowStride
                + (long) unpackSkipPixels * bytesPerPixel
                + (long) (height - 1) * rowStride
                + (long) width * bytesPerPixel;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Texture upload too large: " + width + "x" + height);
        }
        return (int) length;
    }

    private static int pixelReadLength(int width, int height, int format, int type) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int bytesPerPixel = bytesPerPixel(format, type);
        int rowLength = packRowLength > 0 ? packRowLength : width;
        long rowStride = aligned((long) rowLength * bytesPerPixel, packAlignment);
        long length = (long) packSkipRows * rowStride
                + (long) packSkipPixels * bytesPerPixel
                + (long) (height - 1) * rowStride
                + (long) width * bytesPerPixel;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Pixel read too large: " + width + "x" + height);
        }
        return (int) length;
    }

    private static int webGlPixelAlignment(int value) {
        return switch (value) {
            case 1, 2, 4, 8 -> value;
            default -> 1;
        };
    }

    private static long aligned(long value, int alignment) {
        if (alignment <= 1) {
            return value;
        }
        long remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }

    private static int bytesPerPixel(int format, int type) {
        return switch (type) {
            case 0x8033, 0x8034, 0x8035, 0x8367, 0x84FA -> 4;
            default -> componentsForFormat(format) * bytesForType(type);
        };
    }

    private static int componentsForFormat(int format) {
        return switch (format) {
            case 0x1907 -> 3; // GL_RGB
            case 0x1908, 0x80E1 -> 4; // GL_RGBA / GL_BGRA
            case 0x8227 -> 2; // GL_RG
            case 0x84F9 -> 2; // GL_DEPTH_STENCIL
            default -> 1;
        };
    }

    private static int bytesForType(int type) {
        return switch (type) {
            case 0x1403, 0x1402, 0x8D61 -> 2; // unsigned short / short / half float
            case 0x1405, 0x1404, 0x1406, 0x84FA -> 4; // uint / int / float / uint_24_8
            default -> 1;
        };
    }

    private record MappedBuffer(int logicalBuffer, long offset, int access, ByteBuffer buffer) {
        private boolean uploadOnUnmap() {
            return (access & MAP_WRITE_BIT) != 0 && (access & MAP_FLUSH_EXPLICIT_BIT) == 0;
        }
    }

    private static final class UniformScratch {
        private final float[] floats0 = new float[0];
        private final float[] floats1 = new float[1];
        private final float[] floats2 = new float[2];
        private final float[] floats3 = new float[3];
        private final float[] floats4 = new float[4];
        private final float[] floats9 = new float[9];
        private final float[] floats16 = new float[16];
        private final int[] ints0 = new int[0];
        private final int[] ints1 = new int[1];
        private final int[] ints2 = new int[2];
        private final int[] ints3 = new int[3];
        private final int[] ints4 = new int[4];
        private float[] otherFloats;
        private int[] otherInts;

        private float[] floats(int length) {
            return switch (length) {
                case 0 -> floats0;
                case 1 -> floats1;
                case 2 -> floats2;
                case 3 -> floats3;
                case 4 -> floats4;
                case 9 -> floats9;
                case 16 -> floats16;
                default -> {
                    if (otherFloats == null || otherFloats.length != length) {
                        otherFloats = new float[length];
                    }
                    yield otherFloats;
                }
            };
        }

        private int[] ints(int length) {
            return switch (length) {
                case 0 -> ints0;
                case 1 -> ints1;
                case 2 -> ints2;
                case 3 -> ints3;
                case 4 -> ints4;
                default -> {
                    if (otherInts == null || otherInts.length != length) {
                        otherInts = new int[length];
                    }
                    yield otherInts;
                }
            };
        }
    }
}
