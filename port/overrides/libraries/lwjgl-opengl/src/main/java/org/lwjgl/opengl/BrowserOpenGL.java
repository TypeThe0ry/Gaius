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
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.lwjgl.system.MemoryUtil;

/** WebGL2 implementation used by patched LWJGL OpenGL entry points. */
public final class BrowserOpenGL {
    private static final Map<Integer, MappedBuffer> MAPPED_BUFFERS = new HashMap<>();
    private static int unpackAlignment = 4;
    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;
    private static int inventoryWorldRenderFrame;
    private static String inventoryWorldRenderScreen;

    private BrowserOpenGL() {
    }

    @JSBody(script = """
            const gl=window.__gaiusWebGL;
            if (!gl) throw new Error('WebGL2 context is not initialized');
            if (!window.__gaiusGL) {
              window.__gaiusGL={next:1,textures:new Map(),buffers:new Map(),shaders:new Map(),
                programs:new Map(),framebuffers:new Map(),vaos:new Map(),samplers:new Map(),syncs:new Map(),
                bufferSizes:new Map(),bufferBytes:new Map(),bufferVersions:new Map(),boundBuffers:new Map(),
                bufferShadowTouch:new Map(),bufferShadowClock:0,bufferShadowTotalBytes:0,
                shadowRequiredBuffers:new Set(),
                activeTextureUnit:0,textureBindings:new Map(),textureBufferInfo:new Map(),
                textureInfo:new Map(),framebufferColorTextures:new Map(),framebufferColorTextureMisses:new Set(),
                framebufferBindings:{draw:0,read:0},
                colorMask:[true,true,true,true],
                guiDrawDiagnostics:false,guiDrawsRemaining:0,guiCullFaceBatchActive:false,
                guiItemOffscreenScissorDisabled:false,
                enabledCaps:new Set(),knownCaps:new Set(),
                currentProgram:0,programAttribs:new Map(),programVersion:0,
                currentVaoId:0,vaoEmu:new Map(),alignedAttribCache:new Map(),shiftedIndexCache:new Map()};
              window.__gaiusGL.bumpBufferVersion=function(buffer) {
                if (!buffer) return;
                this.bufferVersions.set(buffer,(this.bufferVersions.get(buffer)||0)+1);
                var prefix=(buffer|0)+':';
                var stale=[];
                this.alignedAttribCache.forEach(function(entry,key) {
                  if (key.startsWith(prefix)) {
                    try { window.__gaiusWebGL.deleteBuffer(entry.buffer); } catch (ignored) {}
                    stale.push(key);
                  }
                });
                for (var staleIndex=0; staleIndex<stale.length; staleIndex++) {
                  this.alignedAttribCache.delete(stale[staleIndex]);
                }
                if (this.shiftedIndexCache) {
                  stale=[];
                  this.shiftedIndexCache.forEach(function(entry,key) {
                    if (key.startsWith(prefix)) {
                      try { window.__gaiusWebGL.deleteBuffer(entry.buffer); } catch (ignored) {}
                      stale.push(key);
                    }
                  });
                  for (var indexStale=0; indexStale<stale.length; indexStale++) {
                    this.shiftedIndexCache.delete(stale[indexStale]);
                  }
                }
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
                  attribVersion:1,
                  attribTypeVersion:1,
                  alignedAttribVersion:-1,
                  alignedAttribProgram:-1,
                  alignedAttribGlobalVersion:-1,
                  directAttribVersion:-1,
                  directAttribProgram:-1,
                  directAttribGlobalVersion:-1,
                  directAttribDirty:false,
                  programAttribProgram:-1,
                  programAttribVersion:-1,
                  programAttribTypeVersion:-1,
                  programAttribGlobalVersion:-1,
                  drawAttribPreparedProgram:-1,
                  drawAttribPreparedVersion:-1,
                  drawAttribPreparedGlobalVersion:-1,
                  drawAttribPreparedDirectDirty:-1,
                  drawAttribPreparedMisalignedCount:-1,
                  programAttribCache:new Map()
                };
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
                vao.programAttribVersion=-1;
                vao.drawAttribPreparedVersion=-1;
              };
              window.__gaiusGL.bumpVaoAttribTypeVersion=function(vao){if(!vao)return;vao.attribTypeVersion=((vao.attribTypeVersion||0)+1)|0;if(vao.attribTypeVersion<=0)vao.attribTypeVersion=1;vao.programAttribTypeVersion=-1;};
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
              window.__gaiusGL.setAttribMisaligned=function(vao,index,misaligned){const idx=index|0;if(misaligned)vao.misalignedAttribs.add(idx);else vao.misalignedAttribs.delete(idx);};
              window.__gaiusGL.sameAttribPointer=function(a,b){return !!a&&!!b&&(a.index|0)===(b.index|0)&&(a.size|0)===(b.size|0)&&(a.type|0)===(b.type|0)&&!!a.normalized===!!b.normalized&&(a.stride|0)===(b.stride|0)&&Number(a.offset)===Number(b.offset)&&!!a.integer===!!b.integer&&(a.buffer|0)===(b.buffer|0);};
              window.__gaiusGL.isIntegerAttribType=function(type){type=type|0;return type===0x1404||type===0x1405||type===0x8B53||type===0x8B54||type===0x8B55||type===0x8DC6||type===0x8DC7||type===0x8DC8;};
              window.__gaiusGL.recordDrawCall=function() {
                const calls=((this.drawCallsCount|0)+1)|0;
                const windowCalls=((this.drawWindowCallsCount|0)+1)|0;
                this.drawCallsCount=calls;
                this.drawWindowCallsCount=windowCalls;
                if ((calls & 63) !== 0) {
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
                return (this.textureBindings.get(unit + ':' + target)
                  || this.textureBindings.get(unit + ':' + gl.TEXTURE_2D)
                  || this.textureBindings.get(unit + ':35882')
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
              window.__gaiusGL.withGuiItemOffscreenScissorRepair=function(draw) {
                const gl=window.__gaiusWebGL;
                const repair=this.enabledCaps && this.enabledCaps.has(gl.SCISSOR_TEST)
                  && this.isGuiItemOffscreen512Target();
                if (!repair) {
                  this.restoreGuiItemOffscreenScissor('non-offscreen-draw');
                  draw();
                  return;
                }
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.offscreen512ScissorRepairs=(stats.offscreen512ScissorRepairs||0)+1;
                try {
                  if (!this.guiItemOffscreenScissorDisabled) {
                    gl.disable(gl.SCISSOR_TEST);
                    this.guiItemOffscreenScissorDisabled=true;
                    stats.offscreen512ScissorBatchDisables=(stats.offscreen512ScissorBatchDisables||0)+1;
                  }
                  draw();
                } catch (error) {
                  this.restoreGuiItemOffscreenScissor('exception');
                  throw error;
                }
              };
              window.__gaiusGL.recordTextureUpload=function(kind,target,level,x,y,width,height,internalFormat,format,type,pixels) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                const texture=this.boundTextureId(target);
                const byteLength=pixels && pixels.byteLength ? pixels.byteLength|0 : 0;
                const entry={
                  kind:String(kind),
                  texture:texture,
                  target:target|0,
                  level:level|0,
                  x:x|0,
                  y:y|0,
                  width:width|0,
                  height:height|0,
                  internalFormat:internalFormat|0,
                  format:format|0,
                  type:type|0,
                  byteLength:byteLength,
                  at:Date.now()
                };
                stats.textureUploads=(stats.textureUploads||0)+1;
                stats.textureUploadBytes=(stats.textureUploadBytes||0)+byteLength;
                stats.textureUploadLast=entry;
                const recent=stats.textureUploadRecent || (stats.textureUploadRecent=[]);
                recent.push(entry);
                if (recent.length>64) recent.splice(0,recent.length-64);
                if (texture) {
                  const previous=this.textureInfo.get(texture) || {};
                  const merged=Object.assign({},previous,entry);
                  merged.version=((previous.version||0)+1)|0;
                  if (width>0) merged.width=width|0;
                  if (height>0) merged.height=height|0;
                  this.textureInfo.set(texture,merged);
                  this.invalidateGuiItemAtlasBlitCache(texture,'upload');
                  stats.textureInfo=Array.from(this.textureInfo.entries()).slice(-64).map(function(pair) {
                    return Object.assign({texture:pair[0]|0},pair[1]);
                  });
                }
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
                const fallback=256 * 1024 * 1024;
                const configured=Number(window.__gaiusMaxSingleBufferShadowBytes);
                if (Number.isFinite(configured) && configured > fallback) return Math.floor(configured);
                return fallback;
              };
              window.__gaiusGL.maxTotalBufferShadowBytes=function() {
                const fallback=1024 * 1024 * 1024;
                const configured=Number(window.__gaiusMaxTotalBufferShadowBytes);
                if (Number.isFinite(configured) && configured > fallback) return Math.floor(configured);
                return fallback;
              };
              window.__gaiusGL.deleteBufferShadow=function(buffer) {
                if (!buffer) return;
                const existing=this.bufferBytes.get(buffer);
                if (existing && existing.byteLength) {
                  this.bufferShadowTotalBytes=Math.max(0,(this.bufferShadowTotalBytes||0)-existing.byteLength);
                }
                this.bufferBytes.delete(buffer);
                this.bufferShadowTouch.delete(buffer);
              };
              window.__gaiusGL.dropBufferShadow=function(buffer, reason) {
                if (!buffer) return;
                this.deleteBufferShadow(buffer);
                this.bumpBufferVersion(buffer);
                const skipped=((this.bufferShadowSkippedUnneededCount|0)+1)|0;
                this.bufferShadowSkippedUnneededCount=skipped;
                if (skipped===1 || (skipped & 63)===0) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.bufferShadowSkippedUnneeded=skipped;
                  if (reason && (skipped===1 || (skipped & 1023)===0)) stats.bufferShadowSkippedUnneededReason=reason;
                }
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
                if (this.shadowRequiredBuffers && this.shadowRequiredBuffers.has(buffer|0)) return true;
                if (target===gl.ELEMENT_ARRAY_BUFFER || target===0x8C2A) return true;
                if (target===gl.COPY_READ_BUFFER || target===gl.COPY_WRITE_BUFFER) return true;
                if (target===gl.ARRAY_BUFFER) return this.bufferNeedsArrayShadow(buffer|0);
                return false;
              };
              window.__gaiusGL.shadowBufferDataForTarget=function(target,buffer,data,size) {
                if (this.shouldShadowBufferTarget(target,buffer)) {
                  this.shadowBufferData(buffer,data,size);
                } else {
                  this.dropBufferShadow(buffer,'target:'+target);
                }
              };
              window.__gaiusGL.shadowBufferSubDataForTarget=function(target,buffer,offset,data) {
                if (this.shouldShadowBufferTarget(target,buffer)) {
                  this.shadowBufferSubData(buffer,offset,data);
                } else {
                  this.dropBufferShadow(buffer,'target:'+target);
                }
              };
              window.__gaiusGL.touchBufferShadow=function(buffer, bytes) {
                if (!buffer || !bytes) return;
                this.bufferShadowTouch.set(buffer,++this.bufferShadowClock);
              };
              window.__gaiusGL.trimBufferShadows=function() {
                const limit=this.maxTotalBufferShadowBytes();
                if (!Number.isFinite(limit) || limit <= 0) {
                  const keys=Array.from(this.bufferBytes.keys());
                  for (var allIndex=0; allIndex<keys.length; allIndex++) {
                    this.deleteBufferShadow(keys[allIndex]);
                  }
                  return;
                }
                var total=this.bufferShadowTotalBytes||0;
                if (total <= limit) return;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                while (total > limit && this.bufferBytes.size) {
                  var oldestKey=0;
                  var oldestTouch=Number.POSITIVE_INFINITY;
                  this.bufferBytes.forEach(function(_value,key) {
                    var touch=this.bufferShadowTouch.get(key) || 0;
                    if (touch < oldestTouch) {
                      oldestTouch=touch;
                      oldestKey=key;
                    }
                  }, this);
                  if (!oldestKey) break;
                  const before=this.bufferBytes.get(oldestKey);
                  const beforeBytes=before && before.byteLength ? before.byteLength : 0;
                  this.deleteBufferShadow(oldestKey);
                  total=this.bufferShadowTotalBytes||0;
                  stats.bufferShadowEvictions=(stats.bufferShadowEvictions||0)+1;
                  stats.bufferShadowEvictedBytes=(stats.bufferShadowEvictedBytes||0)+beforeBytes;
                }
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
                if (actual > this.maxSingleBufferShadowBytes()) {
                  stats.bufferShadowSkippedLarge=(stats.bufferShadowSkippedLarge||0)+1;
                  stats.bufferShadowSkippedLargeBytes=(stats.bufferShadowSkippedLargeBytes||0)+actual;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                const copy=new Uint8Array(actual);
                const source=new Uint8Array(data.buffer,data.byteOffset || 0,Math.min(data.byteLength,actual));
                copy.set(source,0);
                this.bufferBytes.set(buffer,copy);
                this.bufferShadowTotalBytes=(this.bufferShadowTotalBytes||0)+copy.byteLength;
                this.touchBufferShadow(buffer,copy.byteLength);
                this.bumpBufferVersion(buffer);
                this.trimBufferShadows();
              };
              window.__gaiusGL.shadowBufferSubData=function(buffer,offset,data) {
                if (!buffer || !data) return;
                const start=Number(offset);
                if (!Number.isFinite(start) || start < 0) return;
                const source=new Uint8Array(data.buffer,data.byteOffset || 0,data.byteLength);
                const end=start+source.byteLength;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
                if (allocation > this.maxSingleBufferShadowBytes()) {
                  this.deleteBufferShadow(buffer);
                  stats.bufferShadowSkippedLarge=(stats.bufferShadowSkippedLarge||0)+1;
                  stats.bufferShadowSkippedLargeBytes=(stats.bufferShadowSkippedLargeBytes||0)+allocation;
                  this.bumpBufferVersion(buffer);
                  return;
                }
                if (!current || current.byteLength < end) {
                  const next=new Uint8Array(allocation);
                  if (current) next.set(current,0);
                  current=next;
                }
                current.set(source,start);
                this.bufferBytes.set(buffer,current);
                if (current.byteLength !== previousLength) {
                  this.bufferShadowTotalBytes=Math.max(
                    0,(this.bufferShadowTotalBytes||0)-previousLength+current.byteLength);
                }
                this.touchBufferShadow(buffer,current.byteLength);
                this.bumpBufferVersion(buffer);
                this.trimBufferShadows();
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
                const current=this.colorMask || [true,true,true,true];
                if (current[0] && current[1] && current[2] && current[3]) return;
                window.__gaiusWebGL.colorMask(true,true,true,true);
                this.colorMask=[true,true,true,true];
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
                const pointer={
                  index:index,
                  size:format.size|0,
                  type:format.type|0,
                  normalized:!!format.normalized,
                  stride:stride,
                  offset:offset,
                  integer:!!format.integer,
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
                  const skips=((this.attribPointerFastSkips||0)+1)|0;
                  this.attribPointerFastSkips=skips;
                  if ((skips & 255)===0) {
                    var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    stats.attribPointerFastSkips=skips;
                  }
                  return;
                }
                vao.attribPointers.set(index,pointer);
                this.setAttribBufferPresence(vao,index,true);
                this.setAttribMisaligned(vao,index,!aligned,vertexBuffer.buffer|0);
                if (typeLayoutChanged) this.bumpVaoAttribTypeVersion(vao);
                if (previousPresence && (!samePointer || previousMisaligned!==!aligned)) {
                  this.bumpVaoAttribVersion(vao);
                }
                if (aligned) {
                  gl.bindBuffer(gl.ARRAY_BUFFER,bufferObject);
                  if (format.integer) {
                    gl.vertexAttribIPointer(index,format.size|0,format.type|0,stride,offset);
                  } else {
                    gl.vertexAttribPointer(index,format.size|0,format.type|0,!!format.normalized,stride,offset);
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
                const active=new Set();
                if (!attribs || !attribs.length) return active;
                for (var i=0;i<attribs.length;i++) {
                  const location=attribs[i] && (attribs[i].location|0);
                  if (location >= 0) active.add(location|0);
                }
                return active;
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
                    var buffer=gl.createBuffer();
                    gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
                    gl.bufferData(gl.ARRAY_BUFFER,repacked,gl.STATIC_DRAW);
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
                    entry={buffer:buffer,stride:alignedStride,layouts:entryLayouts};
                    this.alignedAttribCache.set(key,entry);
                    stats.alignedAttribBuffers=(stats.alignedAttribBuffers||0)+1;
                    stats.alignedAttribBytes=(stats.alignedAttribBytes||0)+repacked.byteLength;
                  }
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
                if (complete) {
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
                    result.push({
                      location:location|0,
                      name:String(info.name),
                      type:info.type|0,
                      integer:this.isIntegerAttribType(info.type)
                    });
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
                if ((vao.programAttribProgram|0)===program
                    && (vao.programAttribTypeVersion|0)===typeVersion
                    && (vao.programAttribGlobalVersion|0)===globalVersion) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.attribTypeFastSkips=(stats.attribTypeFastSkips||0)+1;
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
                vao.programAttribProgram=program;
                vao.programAttribTypeVersion=typeVersion;
                vao.programAttribGlobalVersion=globalVersion;
                if (vao.programAttribCache) {
                  if (repaired && vao.programAttribCache.size) vao.programAttribCache.clear();
                  if (vao.programAttribCache.size > 64) vao.programAttribCache.clear();
                  vao.programAttribCache.set(program|0,{typeVersion:typeVersion|0,globalVersion:globalVersion|0});
                }
                return repaired;
              };
              window.__gaiusGL.prepareDrawAttribs=function(vao) {
                const program=this.currentProgram|0;
                const version=vao.attribVersion||0;
                const globalVersion=this.programVersion||0;
                const directDirty=vao.directAttribDirty ? 1 : 0;
                const missingCount=vao.missingEnabledAttribs ? (vao.missingEnabledAttribs.size|0) : 0;
                const misalignedCount=vao.misalignedAttribs ? (vao.misalignedAttribs.size|0) : 0;
                if (missingCount===0
                    && (vao.drawAttribPreparedProgram|0)===program
                    && (vao.drawAttribPreparedVersion|0)===version
                    && (vao.drawAttribPreparedGlobalVersion|0)===globalVersion
                    && (vao.drawAttribPreparedDirectDirty|0)===directDirty
                    && (vao.drawAttribPreparedMisalignedCount|0)===misalignedCount) {
                  this.drawAttribPrepareFastSkips=((this.drawAttribPrepareFastSkips||0)+1)|0;
                  if ((this.drawAttribPrepareFastSkips & 255)===0) {
                    var skipStats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                    skipStats.drawAttribPrepareFastSkips=this.drawAttribPrepareFastSkips;
                  }
                  return 0;
                }
                const repaired=this.ensureProgramAttribTypes()|0;
                const aligned=this.ensureAlignedAttribs()|0;
                const finalMissing=vao.missingEnabledAttribs ? (vao.missingEnabledAttribs.size|0) : 0;
                if (finalMissing===0) {
                  vao.drawAttribPreparedProgram=program;
                  vao.drawAttribPreparedVersion=vao.attribVersion||0;
                  vao.drawAttribPreparedGlobalVersion=this.programVersion||0;
                  vao.drawAttribPreparedDirectDirty=vao.directAttribDirty ? 1 : 0;
                  vao.drawAttribPreparedMisalignedCount=vao.misalignedAttribs ? (vao.misalignedAttribs.size|0) : 0;
                } else {
                  vao.drawAttribPreparedVersion=-1;
                }
                return (repaired+aligned)|0;
              };
              window.__gaiusGL.withBaseVertexAttribs=function(baseVertex, draw) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const shiftedAttribPointers=[];
                const previousArrayId=this.boundBuffers.get(gl.ARRAY_BUFFER)|0;
                const previousArray=previousArrayId ? this.buffers.get(previousArrayId) : null;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
                      stats.baseVertexBadOffset=(stats.baseVertexBadOffset||0)+1;
                      return;
                    }
                    if (this.bindAttribPointerAtOffset(pointer,shiftedOffset,false)) {
                      shiftedAttribPointers.push(pointer);
                      vao.directAttribDirty=true;
                    } else {
                      stats.baseVertexMissingBuffer=(stats.baseVertexMissingBuffer||0)+1;
                    }
                  }, this);
                  if (shiftedAttribPointers.length) {
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
                    stats.baseVertexDirectRestores=(stats.baseVertexDirectRestores||0)+1;
                    stats.baseVertexDirectRestorePointers=(stats.baseVertexDirectRestorePointers||0)+shiftedAttribPointers.length;
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
              window.__gaiusGL.cacheShiftedIndexBuffer=function(type, offset, count, baseVertex) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const elementBuffer=vao.elementArrayBuffer|0;
                const source=this.bufferBytes.get(elementBuffer);
                const indexBytes=this.indexBytes(type);
                const start=Number(offset);
                const length=count|0;
                const base=baseVertex|0;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                if (!elementBuffer || !source || !indexBytes || length <= 0
                    || !Number.isFinite(start) || start < 0 || (start % indexBytes) !== 0
                    || start + length * indexBytes > source.byteLength) {
                  stats.baseVertexIndexCacheMiss=(stats.baseVertexIndexCacheMiss||0)+1;
                  return null;
                }
                const version=this.bufferVersions.get(elementBuffer|0)||0;
                const key=(elementBuffer|0)+':'+version+':'+(type|0)+':'+start+':'+length+':'+base;
                let entry=this.shiftedIndexCache.get(key);
                if (entry) {
                  stats.baseVertexIndexCacheHits=(stats.baseVertexIndexCacheHits||0)+1;
                  return entry;
                }
                let maxIndex=0;
                let minIndex=2147483647;
                let outputType=type|0;
                let output;
                const wasmHotpath=window.__gaiusWasmHotpath;
                if (wasmHotpath && wasmHotpath.ready && wasmHotpath.shiftIndices) {
                  const wasmShifted=wasmHotpath.shiftIndices(type|0, source, start, length, base);
                  if (wasmShifted && wasmShifted.output) {
                    outputType=wasmShifted.type|0;
                    output=wasmShifted.output;
                    minIndex=wasmShifted.min>>>0;
                    maxIndex=wasmShifted.max>>>0;
                    stats.baseVertexIndexWasm=(stats.baseVertexIndexWasm||0)+1;
                    stats.baseVertexIndexWasmBytes=(stats.baseVertexIndexWasmBytes||0)+(wasmShifted.bytes|0);
                  } else {
                    stats.baseVertexIndexWasmFallback=(stats.baseVertexIndexWasmFallback||0)+1;
                  }
                } else if (wasmHotpath && wasmHotpath.error) {
                  stats.baseVertexIndexWasmUnavailable=(stats.baseVertexIndexWasmUnavailable||0)+1;
                }
                if (!output) {
                  const byteOffset=source.byteOffset + start;
                  let values;
                  if ((type|0) === 0x1401) {
                    values=new Uint8Array(source.buffer,byteOffset,length);
                  } else if ((type|0) === 0x1403) {
                    values=new Uint16Array(source.buffer,byteOffset,length);
                  } else {
                    values=new Uint32Array(source.buffer,byteOffset,length);
                  }
                  for (let i=0;i<length;i++) {
                    const shiftedIndexValue=Number(values[i]) + base;
                    if (shiftedIndexValue < 0 || shiftedIndexValue > 4294967295) {
                      stats.baseVertexIndexOutOfRange=(stats.baseVertexIndexOutOfRange||0)+1;
                      return null;
                    }
                    if (shiftedIndexValue > maxIndex) maxIndex=shiftedIndexValue;
                    if (shiftedIndexValue < minIndex) minIndex=shiftedIndexValue;
                  }
                  if (maxIndex <= 255 && (type|0) === 0x1401) {
                    output=new Uint8Array(length);
                  } else if (maxIndex <= 65535 && (type|0) !== 0x1405) {
                    outputType=0x1403;
                    output=new Uint16Array(length);
                  } else {
                    outputType=0x1405;
                    output=new Uint32Array(length);
                  }
                  for (let i=0;i<length;i++) {
                    output[i]=Number(values[i]) + base;
                  }
                  stats.baseVertexIndexJsFallback=(stats.baseVertexIndexJsFallback||0)+1;
                }
                const buffer=gl.createBuffer();
                gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,buffer);
                gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,output,gl.STATIC_DRAW);
                entry={buffer:buffer,type:outputType,count:length,bytes:output.byteLength,min:minIndex,max:maxIndex};
                this.shiftedIndexCache.set(key,entry);
                stats.baseVertexIndexBuffers=(stats.baseVertexIndexBuffers||0)+1;
                stats.baseVertexIndexBytes=(stats.baseVertexIndexBytes||0)+output.byteLength;
                stats.baseVertexIndexLastMin=minIndex;
                stats.baseVertexIndexLastMax=maxIndex;
                while (this.shiftedIndexCache.size > 4096) {
                  const oldestKey=this.shiftedIndexCache.keys().next().value;
                  const oldest=this.shiftedIndexCache.get(oldestKey);
                  if (oldest && oldest.buffer) {
                    try { gl.deleteBuffer(oldest.buffer); } catch (ignored) {}
                  }
                  this.shiftedIndexCache.delete(oldestKey);
                  stats.baseVertexIndexEvictions=(stats.baseVertexIndexEvictions||0)+1;
                }
                return entry;
              };
              window.__gaiusGL.drawElementsWithBaseVertex=function(mode,count,type,offset,instances,baseVertex) {
                const gl=window.__gaiusWebGL;
                const off=Number(offset);
                const inst=instances|0;
                const base=baseVertex|0;
                this.sampleGuiDraw(mode,count,type,off,inst,base);
                if (base === 0) {
                  if (inst > 1) {
                    gl.drawElementsInstanced(mode,count,type,off,inst);
                  } else {
                    gl.drawElements(mode,count,type,off);
                  }
                  return;
                }
                const extension=this.getBaseVertexExtension();
                if (extension && extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL) {
                  extension.drawElementsInstancedBaseVertexBaseInstanceWEBGL(
                    mode,count,type,off,Math.max(1,inst),base,0);
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.baseVertexExtensionDraws=(stats.baseVertexExtensionDraws||0)+1;
                  return;
                }
                if (extension && extension.drawElementsInstancedBaseVertexWEBGL) {
                  extension.drawElementsInstancedBaseVertexWEBGL(mode,count,type,off,Math.max(1,inst),base);
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.baseVertexExtensionDraws=(stats.baseVertexExtensionDraws||0)+1;
                  return;
                }
                const shiftedIndex=this.cacheShiftedIndexBuffer(type,off,count,base);
                if (shiftedIndex) {
                  const vao=this.getVaoEmu();
                  const originalElement=vao.elementArrayBuffer|0;
                  const originalObject=originalElement ? this.buffers.get(originalElement) : null;
                  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,shiftedIndex.buffer);
                  if (inst > 1) {
                    gl.drawElementsInstanced(mode,count,shiftedIndex.type,0,inst);
                  } else {
                    gl.drawElements(mode,count,shiftedIndex.type,0);
                  }
                  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,originalObject || null);
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.baseVertexIndexDraws=(stats.baseVertexIndexDraws||0)+1;
                  return;
                }
                this.withBaseVertexAttribs(base,function() {
                  if (inst > 1) {
                    gl.drawElementsInstanced(mode,count,type,off,inst);
                  } else {
                    gl.drawElements(mode,count,type,off);
                  }
                });
              };
              window.__gaiusGL.withValidAttribs=function(draw) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                let disabled=null;
                var stats=null;
                const guiDraw=(this.guiDrawsRemaining|0)>0;
                const beginGuiCullFaceBatch=guiDraw
                  && this.enabledCaps.has(gl.CULL_FACE)
                  && !this.guiCullFaceBatchActive;
                try {
                  this.ensureDefaultFramebufferColorWrites();
                  this.prepareDrawAttribs(vao);
                  if (vao.missingEnabledAttribs && vao.missingEnabledAttribs.size) {
                    disabled=[];
                    vao.missingEnabledAttribs.forEach(function(attrib) {
                      const index=attrib|0;
                      gl.disableVertexAttribArray(index);
                      disabled.push(index);
                    });
                    if (disabled.length) {
                      stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
                  this.recordDrawCall();
                  const guiDrawDiag=!!this.guiDrawDiagnostics && guiDraw;
                  const guiDrawState=guiDrawDiag ? this.captureGuiDrawState() : null;
                  draw();
                  if (guiDrawDiag) {
                    this.recordGuiDrawState(guiDrawState, gl.getError()|0);
                  }
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
                    for (var i=0;i<disabled.length;i++) {
                      gl.enableVertexAttribArray(disabled[i]);
                    }
                  }
                }
              };
            }
            """)
    private static native void initializeJs();

    public static void initialize() {
        initializeJs();
        initializeShadowDecisionCache();
        initializeMisalignedBufferRefs();
    }

    @JSBody(script = """
            const s=window.__gaiusGL,g=window.__gaiusWebGL;
            if(!s||s.__shadowDecisionInit)return;
            s.__shadowDecisionInit=true;
            s.bufferShadowPolicyVersion=s.bufferShadowPolicyVersion||1;
            s.bufferShadowDecisionCache=s.bufferShadowDecisionCache||new Map();
            s.bumpBufferShadowPolicyVersion=function(){this.bufferShadowPolicyVersion=((this.bufferShadowPolicyVersion||0)+1)|0;if(this.bufferShadowPolicyVersion<=0)this.bufferShadowPolicyVersion=1;};
            const oldMark=s.markBufferShadowRequired;
            s.markBufferShadowRequired=function(b,r){const id=b|0,had=!!(id&&this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id));oldMark.call(this,b,r);if(id&&!had&&this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id))this.bumpBufferShadowPolicyVersion();};
            const oldSet=s.setAttribMisaligned;
            s.setAttribMisaligned=function(v,i,m){const idx=i|0,had=!!(v&&v.misalignedAttribs&&v.misalignedAttribs.has(idx));oldSet.call(this,v,i,m);const now=!!(v&&v.misalignedAttribs&&v.misalignedAttribs.has(idx));if(had!==now)this.bumpBufferShadowPolicyVersion();};
            s.shouldShadowBufferTarget=function(t,b){const id=b|0;if(!id)return false;if(this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id))return true;if(t===g.ELEMENT_ARRAY_BUFFER||t===0x8C2A||t===g.COPY_READ_BUFFER||t===g.COPY_WRITE_BUFFER)return true;if(t!==g.ARRAY_BUFFER)return false;const p=this.bufferShadowPolicyVersion|0,c=this.bufferShadowDecisionCache,e=c&&c.get(id);if(e&&(e.p|0)===p)return!!e.n;const n=!!this.bufferNeedsArrayShadow(id);if(c){c.set(id,{p:p,n:n});if(c.size>8192)c.clear();}return n;};
            """)
    private static native void initializeShadowDecisionCache();

    @JSBody(script = """
            const s=window.__gaiusGL;
            if(!s||s.__mbrInit)return;
            s.__mbrInit=true;
            s.misalignedBufferRefs=new Map();
            const old=s.newVaoEmu;
            s.newVaoEmu=function(){const v=old.call(this);v.misalignedAttribBuffers=new Map();return v;};
            s.vaoEmu.forEach(function(v){if(v&&!v.misalignedAttribBuffers)v.misalignedAttribBuffers=new Map();});
            s.addMbr=function(b){b|=0;if(!b)return;const p=(this.misalignedBufferRefs.get(b)||0)|0;this.misalignedBufferRefs.set(b,(p+1)|0);if(!p&&this.bumpBufferShadowPolicyVersion)this.bumpBufferShadowPolicyVersion();};
            s.delMbr=function(b){b|=0;if(!b)return;const n=((this.misalignedBufferRefs.get(b)||0)-1)|0;if(n>0)this.misalignedBufferRefs.set(b,n);else{this.misalignedBufferRefs.delete(b);if(this.bumpBufferShadowPolicyVersion)this.bumpBufferShadowPolicyVersion();}};
            s.setAttribMisaligned=function(v,i,m,b){i|=0;const p=v.misalignedAttribBuffers.get(i)|0,n=m?((b==null?((v.attribPointers.get(i)||{}).buffer|0):b)|0):0;if(p&&p!==n)this.delMbr(p);if(m){v.misalignedAttribs.add(i);if(n&&p!==n)this.addMbr(n);if(n)v.misalignedAttribBuffers.set(i,n);}else{v.misalignedAttribs.delete(i);v.misalignedAttribBuffers.delete(i);}};
            s.releaseVaoMisalignedBuffers=function(v){if(!v||!v.misalignedAttribBuffers)return;v.misalignedAttribBuffers.forEach(this.delMbr,this);v.misalignedAttribBuffers.clear();if(v.misalignedAttribs)v.misalignedAttribs.clear();};
            s.bufferNeedsArrayShadow=function(buffer){if(!buffer)return false;const id=buffer|0;if(this.shadowRequiredBuffers&&this.shadowRequiredBuffers.has(id))return true;if(this.misalignedBufferRefs&&((this.misalignedBufferRefs.get(id)||0)>0))return true;let n=false;this.vaoEmu.forEach(function(v){if(n||!v||!v.misalignedAttribs||!v.misalignedAttribs.size)return;v.misalignedAttribs.forEach(function(a){const p=v.attribPointers&&v.attribPointers.get(a|0);if(p&&(p.buffer|0)===id)n=true;});});return n;};
            """)
    private static native void initializeMisalignedBufferRefs();

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            const state=window.__gaiusGL;
            if (state && capability === window.__gaiusWebGL.SCISSOR_TEST
                && state.guiItemOffscreenScissorDisabled) {
              state.restoreGuiItemOffscreenScissor('enable');
            }
            if (state && state.knownCaps.has(capability|0) && state.enabledCaps.has(capability|0)) {
              return;
            }
            if (state) {
              state.knownCaps.add(capability|0);
              state.enabledCaps.add(capability|0);
            }
            window.__gaiusWebGL.enable(capability);
            """)
    public static native void enable(int capability);

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            const state=window.__gaiusGL;
            if (state && capability === window.__gaiusWebGL.SCISSOR_TEST
                && state.guiItemOffscreenScissorDisabled) {
              state.guiItemOffscreenScissorDisabled=false;
            }
            if (state && state.knownCaps.has(capability|0) && !state.enabledCaps.has(capability|0)) {
              return;
            }
            if (state) {
              state.knownCaps.add(capability|0);
              state.enabledCaps.delete(capability|0);
            }
            window.__gaiusWebGL.disable(capability);
            """)
    public static native void disable(int capability);

    @JSBody(params = {"red", "green", "blue", "alpha"},
            script = "window.__gaiusWebGL.clearColor(red,green,blue,alpha);")
    public static native void clearColor(float red, float green, float blue, float alpha);

    @JSBody(params = {"depth"}, script = "window.__gaiusWebGL.clearDepth(depth);")
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

    @JSBody(params = {"red", "green", "blue", "alpha"}, script = """
            const state=window.__gaiusGL;
            if (state) state.colorMask=[!!red,!!green,!!blue,!!alpha];
            window.__gaiusWebGL.colorMask(red,green,blue,alpha);
            """)
    public static native void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    @JSBody(params = {"func"}, script = "window.__gaiusWebGL.depthFunc(func);")
    public static native void depthFunc(int function);

    @JSBody(params = {"enabled"}, script = "window.__gaiusWebGL.depthMask(enabled);")
    public static native void depthMask(boolean enabled);

    @JSBody(params = {"mode", "first", "count"}, script = """
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withGuiItemOffscreenScissorRepair(function() {
              window.__gaiusGL.withValidAttribs(function() {
                gl.drawArrays(mode,first,count);
              });
            });
            """)
    public static native void drawArrays(int mode, int first, int count);

    @JSBody(params = {"mode", "count", "type", "offset"}, script = """
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withGuiItemOffscreenScissorRepair(function() {
              window.__gaiusGL.withValidAttribs(function() {
                gl.drawElements(mode,count,type,Number(offset));
              });
            });
            """)
    public static native void drawElements(int mode, int count, int type, long offset);

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
        switch (parameter) {
            case 0x0CF2 -> unpackRowLength = Math.max(0, value);
            case 0x0CF3 -> unpackSkipRows = Math.max(0, value);
            case 0x0CF4 -> unpackSkipPixels = Math.max(0, value);
            case 0x0CF5 -> {
                browserValue = webGlUnpackAlignment(value);
                unpackAlignment = browserValue;
            }
            default -> {
            }
        }
        pixelStoreiJs(parameter, browserValue);
    }

    @JSBody(params = {"parameter", "value"}, script = "window.__gaiusWebGL.pixelStorei(parameter,value);")
    private static native void pixelStoreiJs(int parameter, int value);

    public static void polygonMode(int face, int mode) {
        // WebGL only supports filled polygons.
    }

    @JSBody(params = {"factor", "units"}, script = "window.__gaiusWebGL.polygonOffset(factor,units);")
    public static native void polygonOffset(float factor, float units);

    @JSBody(params = {"x", "y", "width", "height"},
            script = """
                    const state=window.__gaiusGL;
                    const key=x+','+y+','+width+','+height;
                    if (state && state.viewportKey===key) return;
                    if (state) state.viewportKey=key;
                    window.__gaiusWebGL.viewport(x,y,width,height);
                    """)
    public static native void viewport(int x, int y, int width, int height);

    @JSBody(params = {"x", "y", "width", "height"},
            script = """
                    const state=window.__gaiusGL;
                    const key=x+','+y+','+width+','+height;
                    if (state && state.scissorKey===key) return;
                    if (state) state.scissorKey=key;
                    window.__gaiusWebGL.scissor(x,y,width,height);
                    """)
    public static native void scissor(int x, int y, int width, int height);

    @JSBody(params = {"sourceRgb", "destinationRgb", "sourceAlpha", "destinationAlpha"},
            script = "window.__gaiusWebGL.blendFuncSeparate(sourceRgb,destinationRgb,sourceAlpha,destinationAlpha);")
    public static native void blendFuncSeparate(
            int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);

    @JSBody(params = {"source", "destination"},
            script = "window.__gaiusWebGL.blendFunc(source,destination);")
    public static native void blendFunc(int source, int destination);

    @JSBody(params = {"mode"},
            script = "window.__gaiusWebGL.blendEquation(mode);")
    public static native void blendEquation(int mode);

    @JSBody(params = {"modeRgb", "modeAlpha"},
            script = "window.__gaiusWebGL.blendEquationSeparate(modeRgb,modeAlpha);")
    public static native void blendEquationSeparate(int modeRgb, int modeAlpha);

    @JSBody(params = {"unit"}, script = """
            const state=window.__gaiusGL;
            if (state) state.activeTextureUnit=(unit-0x84C0)|0;
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
            state.framebufferColorTextures.forEach(function(mapped, framebuffer) {
              if ((mapped|0)===(texture|0)) {
                state.framebufferColorTextures.delete(framebuffer|0);
                if (state.framebufferColorTextureMisses) state.framebufferColorTextureMisses.delete(framebuffer|0);
              }
            });
            state.textures.delete(texture);
            """)
    public static native void deleteTexture(int texture);

    @JSBody(params = {"target", "texture"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const webTarget=target===0x8C2A ? gl.TEXTURE_2D : target;
            const object=texture===0?null:state.textures.get(texture);
            const unit=state.activeTextureUnit || 0;
            gl.bindTexture(webTarget,object);
            state.textureBindings.set(unit + ':' + target, texture|0);
            if (target===0x8C2A) {
              state.textureBindings.set(unit + ':' + gl.TEXTURE_2D, texture|0);
              if (object) {
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
              }
            }
            """)
    public static native void bindTexture(int target, int texture);

    @JSBody(params = {"target", "parameter", "value"},
            script = """
                    const gl=window.__gaiusWebGL;
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
              pixels = type === 0x1400
                ? new Int8Array(pixels.buffer, pixels.byteOffset || 0, pixels.byteLength)
                : new Uint8Array(pixels.buffer, pixels.byteOffset || 0, pixels.byteLength);
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
        texSubImage2DJs(target, level, x, y, width, height, format, type,
                pointerBytes(pixels, textureUploadLength(width, height, format, type)));
    }

    @JSBody(params = {
            "target", "level", "x", "y", "width", "height", "format", "type", "pixels"
    }, script = """
            if (format === 0x1903 && type === 0x1400) {
              format = 0x8D94;
            }
            if (pixels !== null && pixels !== undefined) {
              pixels = type === 0x1400
                ? new Int8Array(pixels.buffer, pixels.byteOffset || 0, pixels.byteLength)
                : new Uint8Array(pixels.buffer, pixels.byteOffset || 0, pixels.byteLength);
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

    @JSBody(params = {"buffer"}, script = """
            const state=window.__gaiusGL, object=state.buffers.get(buffer);
            if (object) window.__gaiusWebGL.deleteBuffer(object); state.buffers.delete(buffer);
            state.bufferSizes.delete(buffer);
            state.deleteBufferShadow(buffer);
            if (state.shadowRequiredBuffers) state.shadowRequiredBuffers.delete(buffer|0);
            if (state.misalignedBufferRefs) state.misalignedBufferRefs.delete(buffer|0);
            if (state.bufferShadowDecisionCache) state.bufferShadowDecisionCache.delete(buffer|0);
            state.bufferVersions.delete(buffer);
            state.bumpBufferVersion(buffer);
            state.vaoEmu.forEach(function(vao) {
              if ((vao.elementArrayBuffer|0)===(buffer|0)) vao.elementArrayBuffer=0;
              vao.attribPointers.forEach(function(pointer, attrib) {
                if ((pointer.buffer|0)===(buffer|0)) {
                  state.setAttribBufferPresence(vao,attrib|0,false);
                  state.setAttribMisaligned(vao,attrib|0,false);
                  vao.attribPointers.delete(attrib|0);
                }
              });
              vao.vertexBuffers.forEach(function(vertexBuffer, binding) {
                if ((vertexBuffer.buffer|0)===(buffer|0)) vao.vertexBuffers.delete(binding|0);
              });
              state.bumpVaoAttribVersion(vao);
            });
            """)
    public static native void deleteBuffer(int buffer);

    @JSBody(params = {"target", "buffer"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,current=state.boundBuffers.get(target)|0;
            if (target===gl.ELEMENT_ARRAY_BUFFER) {
              const vao=state.getVaoEmu();
              if (buffer) state.markBufferShadowRequired(buffer,'element-array');
              if ((vao.elementArrayBuffer|0)===(buffer|0)) {
                state.boundBuffers.set(target,buffer);
                return;
              }
              gl.bindBuffer(target,buffer===0?null:state.buffers.get(buffer));
              vao.elementArrayBuffer=buffer|0;
              state.boundBuffers.set(target,buffer);
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
            const buffer=state.boundBuffers.get(target)|0;
            const requested=Number(size);
            const actual=target===0x8A11 ? Math.max(requested,256) : requested;
            gl.bufferData(target,actual,usage);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              state.shadowBufferDataForTarget(target,buffer,null,actual);
            }
            """)
    public static native void bufferData(int target, long size, int usage);

    public static void bufferData(int target, ByteBuffer data, int usage) {
        bufferDataJs(target, bytes(data), usage);
    }

    @JSBody(params = {"target", "data", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const buffer=state.boundBuffers.get(target)|0;
            let upload=data;
            let actual=data ? data.byteLength : 0;
            if (target===0x8A11 && actual < 256) {
              const padded=new Int8Array(256);
              if (data) padded.set(data,0);
              upload=padded;
              actual=256;
            }
            gl.bufferData(target,upload,usage);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              state.shadowBufferDataForTarget(target,buffer,upload,actual);
            }
            """)
    private static native void bufferDataJs(int target, Int8Array data, int usage);

    public static void bufferSubData(int target, long offset, ByteBuffer data) {
        bufferSubDataJs(target, offset, bytes(data));
    }

    @JSBody(params = {"target", "offset", "data"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            gl.bufferSubData(target,Number(offset),data);
            const buffer=state.boundBuffers.get(target)|0;
            if (buffer && data) {
              const end=Number(offset)+data.byteLength;
              const known=state.bufferSizes.get(buffer)||0;
              if (end > known) state.bufferSizes.set(buffer,end);
              state.shadowBufferSubDataForTarget(target,buffer,Number(offset),data);
            }
            """)
    private static native void bufferSubDataJs(int target, long offset, Int8Array data);

    public static void namedBufferData(int buffer, long size, int usage) {
        namedBufferDataSizeJs(buffer, size, usage);
    }

    @JSBody(params = {"buffer", "size", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previous=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            const requested=Number(size);
            const actual=Math.max(requested,256);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferData(gl.COPY_WRITE_BUFFER,actual,usage);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              if (state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(buffer|0)) {
                state.shadowBufferData(buffer,null,actual);
              } else {
                state.dropBufferShadow(buffer,'named-buffer');
              }
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferDataSizeJs(int buffer, long size, int usage);

    public static void namedBufferData(int buffer, ByteBuffer data, int usage) {
        namedBufferDataJs(buffer, bytes(data), usage);
    }

    @JSBody(params = {"buffer", "data", "usage"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previous=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            let upload=data;
            let actual=data ? data.byteLength : 0;
            if (actual < 256) {
              const padded=new Int8Array(256);
              if (data) padded.set(data,0);
              upload=padded;
              actual=256;
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferData(gl.COPY_WRITE_BUFFER,upload,usage);
            if (buffer) {
              state.bufferSizes.set(buffer,actual);
              if (state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(buffer|0)) {
                state.shadowBufferData(buffer,upload,actual);
              } else {
                state.dropBufferShadow(buffer,'named-buffer');
              }
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferDataJs(int buffer, Int8Array data, int usage);

    public static void namedBufferSubData(int buffer, long offset, ByteBuffer data) {
        namedBufferSubDataJs(buffer, offset, bytes(data));
    }

    @JSBody(params = {"buffer", "offset", "data"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previous=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(buffer));
            gl.bufferSubData(gl.COPY_WRITE_BUFFER,Number(offset),data);
            if (buffer && data) {
              const end=Number(offset)+data.byteLength;
              const known=state.bufferSizes.get(buffer)||0;
              if (end > known) state.bufferSizes.set(buffer,end);
              if (state.shadowRequiredBuffers && state.shadowRequiredBuffers.has(buffer|0)) {
                state.shadowBufferSubData(buffer,Number(offset),data);
              } else {
                state.dropBufferShadow(buffer,'named-buffer');
              }
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferSubDataJs(int buffer, long offset, Int8Array data);

    public static ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported WebGL mapped buffer length: " + length);
        }
        ByteBuffer buffer = MemoryUtil.memAlloc((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(target, new MappedBuffer(offset, buffer));
        return buffer;
    }

    public static boolean unmapBuffer(int target) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(target);
        if (mapped == null) {
            return true;
        }
        bufferSubDataJs(target, mapped.offset, allBytes(mapped.buffer));
        MemoryUtil.memFree(mapped.buffer);
        return true;
    }

    public static void flushMappedBufferRange(int target, long offset, long length) {
        MappedBuffer mapped = MAPPED_BUFFERS.get(target);
        if (mapped == null || length <= 0L) {
            return;
        }
        long absoluteOffset = mapped.offset + offset;
        bufferSubDataJs(target, absoluteOffset, bytesSlice(mapped.buffer, offset, length));
    }

    public static ByteBuffer mapNamedBufferRange(int buffer, long offset, long length, int access) {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported WebGL mapped buffer length: " + length);
        }
        ByteBuffer byteBuffer = MemoryUtil.memAlloc((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(namedBufferKey(buffer), new MappedBuffer(offset, byteBuffer));
        return byteBuffer;
    }

    public static boolean unmapNamedBuffer(int buffer) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(namedBufferKey(buffer));
        if (mapped == null) {
            return true;
        }
        namedBufferSubDataJs(buffer, mapped.offset, allBytes(mapped.buffer));
        MemoryUtil.memFree(mapped.buffer);
        return true;
    }

    public static void flushMappedNamedBufferRange(int buffer, long offset, long length) {
        MappedBuffer mapped = MAPPED_BUFFERS.get(namedBufferKey(buffer));
        if (mapped == null || length <= 0L) {
            return;
        }
        long absoluteOffset = mapped.offset + offset;
        namedBufferSubDataJs(buffer, absoluteOffset, bytesSlice(mapped.buffer, offset, length));
    }

    private static int namedBufferKey(int buffer) {
        return 0x40000000 | buffer;
    }

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
                .replace("(gl_VertexID >> 3) / 1000.0", "float(gl_VertexID >> 3) / 1000.0");
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
            window.__gaiusWebGL.linkProgram(state.programs.get(program));
            state.refreshProgramAttribs(program|0);
            state.programVersion=((state.programVersion||0)+1)|0;
            """)
    public static native void linkProgram(int program);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL;
            if ((state.currentProgram|0)===(program|0)) {
              return;
            }
            state.currentProgram=program|0;
            window.__gaiusWebGL.useProgram(program===0?null:state.programs.get(program));
            """)
    public static native void useProgram(int program);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL, object=state.programs.get(program);
            if (object) window.__gaiusWebGL.deleteProgram(object); state.programs.delete(program);
            state.programAttribs.delete(program|0);
            if ((state.currentProgram|0)===(program|0)) state.currentProgram=0;
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
            const gl=window.__gaiusWebGL, object=gl.getUniformLocation(window.__gaiusGL.programs.get(program),name);
            if (object===null) return -1;
            const state=window.__gaiusGL, id=state.next++;
            if (!state.uniforms) state.uniforms=new Map(); state.uniforms.set(id,object); return id|0;
            """)
    private static native int getUniformLocationJs(int program, String name);

    @JSBody(params = {"location", "value"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1i(object,value);
            """)
    public static native void uniform1i(int location, int value);

    @JSBody(params = {"location", "value"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1f(object,value);
            """)
    public static native void uniform1f(int location, float value);

    @JSBody(params = {"location", "x", "y"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2f(object,x,y);
            """)
    public static native void uniform2f(int location, float x, float y);

    @JSBody(params = {"location", "x", "y", "z"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3f(object,x,y,z);
            """)
    public static native void uniform3f(int location, float x, float y, float z);

    @JSBody(params = {"location", "x", "y", "z", "w"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4f(object,x,y,z,w);
            """)
    public static native void uniform4f(int location, float x, float y, float z, float w);

    @JSBody(params = {"location", "x", "y"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2i(object,x,y);
            """)
    public static native void uniform2i(int location, int x, int y);

    @JSBody(params = {"location", "x", "y", "z"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3i(object,x,y,z);
            """)
    public static native void uniform3i(int location, int x, int y, int z);

    @JSBody(params = {"location", "x", "y", "z", "w"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4i(object,x,y,z,w);
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
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1fv(object,values);
            """)
    private static native void uniform1fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2fv(object,values);
            """)
    private static native void uniform2fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3fv(object,values);
            """)
    private static native void uniform3fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4fv(object,values);
            """)
    private static native void uniform4fvJs(int location, Float32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform1iv(object,values);
            """)
    private static native void uniform1ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform2iv(object,values);
            """)
    private static native void uniform2ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform3iv(object,values);
            """)
    private static native void uniform3ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniform4iv(object,values);
            """)
    private static native void uniform4ivJs(int location, Int32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniformMatrix2fv(object,transpose,values);
            """)
    private static native void uniformMatrix2fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniformMatrix3fv(object,transpose,values);
            """)
    private static native void uniformMatrix3fvJs(int location, boolean transpose, Float32Array values);

    @JSBody(params = {"location", "transpose", "values"}, script = """
            const object=window.__gaiusGL.uniforms&&window.__gaiusGL.uniforms.get(location);
            if (object!==undefined) window.__gaiusWebGL.uniformMatrix4fv(object,transpose,values);
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
              const skips=((state.enableAttribFastSkips||0)+1)|0;
              state.enableAttribFastSkips=skips;
              if ((skips & 255)===0) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.enableAttribFastSkips=skips;
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
                const skips=((state.disableAttribFastSkips||0)+1)|0;
                state.disableAttribFastSkips=skips;
                if ((skips & 255)===0) {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.disableAttribFastSkips=skips;
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
            const normalizedValue=!!normalized;
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
              || !!previousPointer.integer
              || (previousPointer.size|0)!==sizeValue
              || (previousPointer.type|0)!==typeValue;
            const samePointer=!!previousPointer
              && (previousPointer.index|0)===idx
              && (previousPointer.size|0)===sizeValue
              && (previousPointer.type|0)===typeValue
              && !!previousPointer.normalized===normalizedValue
              && (previousPointer.stride|0)===strideValue
              && Number(previousPointer.offset)===numericOffset
              && !previousPointer.integer
              && (previousPointer.buffer|0)===(buffer|0);
            if (samePointer && previousMisaligned===misaligned && previousPresence===present) {
              const skips=((state.attribPointerFastSkips||0)+1)|0;
              state.attribPointerFastSkips=skips;
              if ((skips & 255)===0) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.attribPointerFastSkips=skips;
              }
              return;
            }
            const pointer={
              index:idx,
              size:sizeValue,
              type:typeValue,
              normalized:normalizedValue,
              stride:strideValue,
              offset:numericOffset,
              integer:false,
              buffer:buffer|0
            };
            vao.attribPointers.set(idx,pointer);
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
              gl.vertexAttribPointer(index,sizeValue,typeValue,normalizedValue,strideValue,numericOffset);
            } else {
              if (buffer) state.markBufferShadowRequired(buffer,'misaligned-attrib-pointer');
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
            }
            """)
    public static native void vertexAttribPointer(
            int index, int size, int type, boolean normalized, int stride, long offset);

    @JSBody(params = {"index", "size", "type", "stride", "offset"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,vao=state.getVaoEmu();
            const idx=index|0;
            const buffer=state.boundBuffers.get(gl.ARRAY_BUFFER)|0;
            const sizeValue=size|0;
            const typeValue=type|0;
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
              || !previousPointer.integer
              || (previousPointer.size|0)!==sizeValue
              || (previousPointer.type|0)!==typeValue;
            const samePointer=!!previousPointer
              && (previousPointer.index|0)===idx
              && (previousPointer.size|0)===sizeValue
              && (previousPointer.type|0)===typeValue
              && !previousPointer.normalized
              && (previousPointer.stride|0)===strideValue
              && Number(previousPointer.offset)===numericOffset
              && !!previousPointer.integer
              && (previousPointer.buffer|0)===(buffer|0);
            if (samePointer && previousMisaligned===misaligned && previousPresence===present) {
              const skips=((state.attribPointerFastSkips||0)+1)|0;
              state.attribPointerFastSkips=skips;
              if ((skips & 255)===0) {
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.attribPointerFastSkips=skips;
              }
              return;
            }
            const pointer={
              index:idx,
              size:sizeValue,
              type:typeValue,
              normalized:false,
              stride:strideValue,
              offset:numericOffset,
              integer:true,
              buffer:buffer|0
            };
            vao.attribPointers.set(idx,pointer);
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
              gl.vertexAttribIPointer(index,sizeValue,typeValue,strideValue,numericOffset);
            } else {
              if (buffer) state.markBufferShadowRequired(buffer,'misaligned-attrib-pointer');
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
            }
            """)
    public static native void vertexAttribIPointer(
            int index, int size, int type, int stride, long offset);

    @JSBody(params = {"index", "divisor"}, script = "window.__gaiusWebGL.vertexAttribDivisor(index,divisor);")
    public static native void vertexAttribDivisor(int index, int divisor);

    @JSBody(params = {"binding", "buffer", "offset", "stride"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            const key=binding|0;
            const previous=vao.vertexBuffers.get(key);
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
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                stats.vertexBufferFastSkips=(stats.vertexBufferFastSkips||0)+1;
                return;
              }
              vao.vertexBuffers.set(key,{
                buffer: buffer|0,
                offset: nextOffset,
                stride: nextStride
              });
            }
            state.applyVertexBinding(key);
            """)
    public static native void bindVertexBuffer(int binding, int buffer, long offset, int stride);

    @JSBody(params = {"index", "binding"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            const idx=index|0;
            const next=binding|0;
            const previous=vao.attribBindings.has(idx) ? (vao.attribBindings.get(idx)|0) : idx;
            if (previous===next) {
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.attribBindingFastSkips=(stats.attribBindingFastSkips||0)+1;
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
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.attribFormatFastSkips=(stats.attribFormatFastSkips||0)+1;
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
              var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.attribFormatFastSkips=(stats.attribFormatFastSkips||0)+1;
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
            state.vaoEmu.set(id,state.newVaoEmu());
            return id|0;
            """)
    public static native int genVertexArray();

    @JSBody(params = {"array"}, script = """
            const state=window.__gaiusGL;
            if ((state.currentVaoId|0)===(array|0)) {
              return;
            }
            state.currentVaoId=array|0;
            window.__gaiusWebGL.bindVertexArray(array===0?null:state.vaos.get(array));
            """)
    public static native void bindVertexArray(int array);

    @JSBody(params = {"array"}, script = """
            const state=window.__gaiusGL, object=state.vaos.get(array);
            if (object) window.__gaiusWebGL.deleteVertexArray(object); state.vaos.delete(array);
            state.releaseVaoMisalignedBuffers(state.vaoEmu.get(array));
            state.vaoEmu.delete(array);
            if (state.currentVaoId===(array|0)) state.currentVaoId=0;
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
            gl.bindFramebuffer(target,framebuffer===0?null:state.framebuffers.get(framebuffer));
            if (target===0x8D40) {
              state.framebufferBindings.draw=framebuffer|0;
              state.framebufferBindings.read=framebuffer|0;
            } else if (target===0x8CA9) {
              state.framebufferBindings.draw=framebuffer|0;
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
              }
            }
            """)
    public static native void framebufferTexture2D(
            int target, int attachment, int textureTarget, int texture, int level);

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
            if (state.framebufferBindings.draw===framebuffer) state.framebufferBindings.draw=0;
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
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withGuiItemOffscreenScissorRepair(function() {
              window.__gaiusGL.withValidAttribs(function() {
                gl.drawArraysInstanced(mode,first,count,instances);
              });
            });
            """)
    public static native void drawArraysInstanced(int mode, int first, int count, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "instances"}, script = """
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withGuiItemOffscreenScissorRepair(function() {
              window.__gaiusGL.withValidAttribs(function() {
                gl.drawElementsInstanced(mode,count,type,Number(offset),instances);
              });
            });
            """)
    public static native void drawElementsInstanced(
            int mode, int count, int type, long offset, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "baseVertex"}, script = """
            window.__gaiusGL.withGuiItemOffscreenScissorRepair(function() {
              window.__gaiusGL.withValidAttribs(function() {
                window.__gaiusGL.drawElementsWithBaseVertex(mode,count,type,offset,1,baseVertex);
              });
            });
            """)
    public static native void drawElementsBaseVertex(
            int mode, int count, int type, long offset, int baseVertex);

    @JSBody(params = {"mode", "count", "type", "offset", "instances", "baseVertex"}, script = """
            window.__gaiusGL.withGuiItemOffscreenScissorRepair(function() {
              window.__gaiusGL.withValidAttribs(function() {
                window.__gaiusGL.drawElementsWithBaseVertex(mode,count,type,offset,instances,baseVertex);
              });
            });
            """)
    public static native void drawElementsInstancedBaseVertex(
            int mode, int count, int type, long offset, int instances, int baseVertex);

    @JSBody(params = {"target", "internalFormat", "buffer"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            if (target !== 0x8C2A) {
              return;
            }
            const unit=state.activeTextureUnit || 0;
            const texture=(state.textureBindings.get(unit + ':35882')
              || state.textureBindings.get(unit + ':' + gl.TEXTURE_2D) || 0)|0;
            const object=texture===0?null:state.textures.get(texture);
            if (!object) {
              return;
            }
            const previousActive=gl.getParameter(gl.ACTIVE_TEXTURE);
            gl.activeTexture(gl.TEXTURE0 + unit);
            gl.bindTexture(gl.TEXTURE_2D,object);
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
                : new Uint8Array(source.buffer,source.byteOffset || 0,paddedLength);
            } else {
              upload=signedInteger ? new Int8Array(paddedLength) : new Uint8Array(paddedLength);
            }
            const previousAlignment=gl.getParameter(gl.UNPACK_ALIGNMENT);
            const previousRowLength=gl.getParameter(gl.UNPACK_ROW_LENGTH);
            const previousSkipRows=gl.getParameter(gl.UNPACK_SKIP_ROWS);
            const previousSkipPixels=gl.getParameter(gl.UNPACK_SKIP_PIXELS);
            try {
              gl.pixelStorei(gl.UNPACK_ALIGNMENT,1);
              gl.pixelStorei(gl.UNPACK_ROW_LENGTH,0);
              gl.pixelStorei(gl.UNPACK_SKIP_ROWS,0);
              gl.pixelStorei(gl.UNPACK_SKIP_PIXELS,0);
              gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
              gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
              gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
              gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
              gl.texImage2D(gl.TEXTURE_2D,0,webInternalFormat,width,height,0,webFormat,webType,upload);
            } finally {
              gl.pixelStorei(gl.UNPACK_ALIGNMENT,previousAlignment);
              gl.pixelStorei(gl.UNPACK_ROW_LENGTH,previousRowLength);
              gl.pixelStorei(gl.UNPACK_SKIP_ROWS,previousSkipRows);
              gl.pixelStorei(gl.UNPACK_SKIP_PIXELS,previousSkipPixels);
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
            gl.activeTexture(previousActive);
            state.activeTextureUnit=(previousActive-gl.TEXTURE0)|0;
            """)
    public static native void texBuffer(int target, int internalFormat, int buffer);

    @JSBody(params = {"target", "index", "buffer", "offset", "size"}, script = """
            const state=window.__gaiusGL;
            const bufferSize=state.bufferSizes.get(buffer)||Number(size);
            const available=Math.max(0,bufferSize-Number(offset));
            const range=target===0x8A11 && available>Number(size)
              ? Math.min(available,Math.max(Number(size),256))
              : Number(size);
            window.__gaiusWebGL.bindBufferRange(
              target,index,buffer===0?null:state.buffers.get(buffer),Number(offset),range);
            """)
    public static native void bindBufferRange(
            int target, int index, int buffer, long offset, long size);

    @JSBody(params = {"target", "index", "buffer"}, script = """
            const state=window.__gaiusGL;
            window.__gaiusWebGL.bindBufferBase(
              target,index,buffer===0?null:state.buffers.get(buffer));
            """)
    public static native void bindBufferBase(int target, int index, int buffer);

    @JSBody(params = {"sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
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
                state.dropBufferShadow(targetBuffer,source ? 'copy-target:'+targetTarget : 'copy-missing-source');
              }
            }
            """)
    public static native void copyBufferSubData(
            int sourceTarget, int targetTarget, long sourceOffset, long targetOffset, long size);

    @JSBody(params = {"sourceBuffer", "targetBuffer", "sourceOffset", "targetOffset", "size"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const previousRead=gl.getParameter(gl.COPY_READ_BUFFER_BINDING);
            const previousWrite=gl.getParameter(gl.COPY_WRITE_BUFFER_BINDING);
            gl.bindBuffer(gl.COPY_READ_BUFFER,state.buffers.get(sourceBuffer));
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,state.buffers.get(targetBuffer));
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
                state.dropBufferShadow(targetBuffer,source ? 'named-copy-target' : 'named-copy-missing-source');
              }
            }
            gl.bindBuffer(gl.COPY_READ_BUFFER,previousRead);
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previousWrite);
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
            window.__gaiusWebGL.bindSampler(
              unit,sampler===0?null:window.__gaiusGL.samplers.get(sampler));
            """)
    public static native void bindSampler(int unit, int sampler);

    @JSBody(params = {"sampler"}, script = """
            const state=window.__gaiusGL, object=state.samplers.get(sampler);
            if (object) window.__gaiusWebGL.deleteSampler(object); state.samplers.delete(sampler);
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

    @JSBody(params = {"condition", "flags"}, script = """
            const state=window.__gaiusGL,id=state.next++;
            state.syncs.set(id,window.__gaiusWebGL.fenceSync(condition,flags)); return id;
            """)
    public static native long fenceSync(int condition, int flags);

    @JSBody(params = {"sync", "flags", "timeout"}, script = """
            const object=window.__gaiusGL.syncs.get(Number(sync));
            return object?window.__gaiusWebGL.clientWaitSync(object,flags,0):0x911A;
            """)
    public static native int clientWaitSync(long sync, int flags, long timeout);

    @JSBody(params = {"sync"}, script = """
            const state=window.__gaiusGL, object=state.syncs.get(Number(sync));
            if (object) window.__gaiusWebGL.deleteSync(object); state.syncs.delete(Number(sync));
            """)
    public static native void deleteSync(long sync);

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
            var counters = window.__gaiusMinecraftCounters;
            if (!counters) {
              counters = {};
              window.__gaiusMinecraftCounters = counters;
            }
            const key = detail == null ? event : event + ":" + detail;
            const count = (counters[key] || 0) + 1;
            counters[key] = count;
            var events = window.__gaiusMinecraftEvents;
            if (!events) {
              events = [];
              window.__gaiusMinecraftEvents = events;
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
        ByteBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array allBytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        copy.clear();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array bytesSlice(ByteBuffer buffer, long offset, long length) {
        if (offset < 0L || length < 0L || offset + length > buffer.capacity() || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer flush range: " + offset + " + " + length);
        }
        ByteBuffer copy = buffer.duplicate();
        copy.position((int) offset);
        copy.limit((int) (offset + length));
        byte[] data = new byte[(int) length];
        copy.get(data);
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array pointerBytes(long address, int length) {
        if (address == 0L) {
            return null;
        }
        if (length <= 0) {
            return Int8Array.fromJavaArray(new byte[0]);
        }
        ByteBuffer bytes = MemoryUtil.memByteBuffer(address, length);
        byte[] data = new byte[length];
        bytes.get(data);
        return Int8Array.fromJavaArray(data);
    }

    private static Float32Array floats(FloatBuffer buffer) {
        FloatBuffer copy = buffer.duplicate();
        float[] data = new float[copy.remaining()];
        copy.get(data);
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array ints(IntBuffer buffer) {
        IntBuffer copy = buffer.duplicate();
        int[] data = new int[copy.remaining()];
        copy.get(data);
        return Int32Array.fromJavaArray(data);
    }

    private static Float32Array pointerFloats(long address, int count) {
        if (address == 0L) {
            return null;
        }
        if (count <= 0) {
            return Float32Array.fromJavaArray(new float[0]);
        }
        FloatBuffer floats = MemoryUtil.memByteBuffer(address, count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] data = new float[count];
        floats.get(data);
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array pointerInts(long address, int count) {
        if (address == 0L) {
            return null;
        }
        if (count <= 0) {
            return Int32Array.fromJavaArray(new int[0]);
        }
        IntBuffer ints = MemoryUtil.memByteBuffer(address, count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        int[] data = new int[count];
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

    private static int webGlUnpackAlignment(int value) {
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

    private record MappedBuffer(long offset, ByteBuffer buffer) {
    }
}
