package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList.WorldListEntry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
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

    private BrowserOpenGL() {
    }

    @JSBody(script = """
            const gl=window.__gaiusWebGL;
            if (!gl) throw new Error('WebGL2 context is not initialized');
            if (!window.__gaiusGL) {
              window.__gaiusGL={next:1,textures:new Map(),buffers:new Map(),shaders:new Map(),
                programs:new Map(),framebuffers:new Map(),vaos:new Map(),samplers:new Map(),syncs:new Map(),
                bufferSizes:new Map(),bufferBytes:new Map(),bufferVersions:new Map(),boundBuffers:new Map(),
                activeTextureUnit:0,textureBindings:new Map(),textureBufferInfo:new Map(),
                textureInfo:new Map(),
                framebufferBindings:{draw:0,read:0},
                currentProgram:0,programAttribs:new Map(),
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
              window.__gaiusGL.componentBytes=function(type) {
                switch (type|0) {
                  case 0x1400: return 1;
                  case 0x1401: return 1;
                  case 0x1402: return 2;
                  case 0x1403: return 2;
                  case 0x1404: return 4;
                  case 0x1405: return 4;
                  case 0x1406: return 4;
                  case 0x140B: return 2;
                  default: return 4;
                }
              };
              window.__gaiusGL.align=function(value, alignment) {
                const a=Math.max(1,alignment|0);
                return Math.ceil(Number(value)/a)*a;
              };
              window.__gaiusGL.isIntegerAttribType=function(type) {
                switch (type|0) {
                  case 0x1404: // INT
                  case 0x1405: // UNSIGNED_INT
                  case 0x8B53: // INT_VEC2
                  case 0x8B54: // INT_VEC3
                  case 0x8B55: // INT_VEC4
                  case 0x8DC6: // UNSIGNED_INT_VEC2
                  case 0x8DC7: // UNSIGNED_INT_VEC3
                  case 0x8DC8: // UNSIGNED_INT_VEC4
                    return true;
                  default:
                    return false;
                }
              };
              window.__gaiusGL.recordDrawCall=function() {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                const now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                stats.drawCalls=(stats.drawCalls||0)+1;
                stats.drawWindowCalls=(stats.drawWindowCalls||0)+1;
                if (!stats.drawWindowStartedAt) {
                  stats.drawWindowStartedAt=now;
                }
                const elapsed=now-stats.drawWindowStartedAt;
                if (elapsed >= 1000) {
                  stats.drawCallsPerSecond=Math.round((stats.drawWindowCalls*1000/elapsed)*10)/10;
                  stats.drawWindowCalls=0;
                  stats.drawWindowStartedAt=now;
                }
              };
              window.__gaiusGL.boundTextureId=function(target) {
                const gl=window.__gaiusWebGL;
                const unit=this.activeTextureUnit || 0;
                return (this.textureBindings.get(unit + ':' + target)
                  || this.textureBindings.get(unit + ':' + gl.TEXTURE_2D)
                  || this.textureBindings.get(unit + ':35882')
                  || 0)|0;
              };
              window.__gaiusGL.recordTextureUpload=function(kind,target,level,x,y,width,height,internalFormat,format,type,pixels) {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
                  if (width>0) merged.width=width|0;
                  if (height>0) merged.height=height|0;
                  this.textureInfo.set(texture,merged);
                  stats.textureInfo=Array.from(this.textureInfo.entries()).slice(-64).map(function(pair) {
                    return Object.assign({texture:pair[0]|0},pair[1]);
                  });
                }
              };
              window.__gaiusGL.recordTextureError=function(kind,target,level,width,height,format,type,pixels,error) {
                const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
              window.__gaiusGL.shadowBufferData=function(buffer,data,size) {
                if (!buffer) return;
                const actual=Number(size !== undefined && size !== null ? size : (data ? data.byteLength : 0));
                if (!Number.isFinite(actual) || actual < 0 || actual > 67108864) {
                  this.bufferBytes.delete(buffer);
                  return;
                }
                const copy=new Uint8Array(actual);
                if (data) {
                  const source=new Uint8Array(data.buffer,data.byteOffset || 0,Math.min(data.byteLength,actual));
                  copy.set(source,0);
                }
                this.bufferBytes.set(buffer,copy);
                this.bumpBufferVersion(buffer);
              };
              window.__gaiusGL.shadowBufferSubData=function(buffer,offset,data) {
                if (!buffer || !data) return;
                const start=Number(offset);
                if (!Number.isFinite(start) || start < 0) return;
                const source=new Uint8Array(data.buffer,data.byteOffset || 0,data.byteLength);
                const end=start+source.byteLength;
                if (end > 67108864) {
                  this.bufferBytes.delete(buffer);
                  return;
                }
                let current=this.bufferBytes.get(buffer);
                const known=this.bufferSizes.get(buffer) || 0;
                if (!current || current.byteLength < end) {
                  const next=new Uint8Array(Math.max(end,known));
                  if (current) next.set(current,0);
                  current=next;
                }
                current.set(source,start);
                this.bufferBytes.set(buffer,current);
                this.bumpBufferVersion(buffer);
              };
              window.__gaiusGL.getVaoEmu=function() {
                const id=this.currentVaoId|0;
                let vao=this.vaoEmu.get(id);
                if (!vao) {
                  vao={
                    attribBindings:new Map(),
                    attribFormats:new Map(),
                    attribPointers:new Map(),
                    vertexBuffers:new Map(),
                    enabledAttribs:new Set(),
                    attribHasBuffer:new Set(),
                    elementArrayBuffer:0
                  };
                  this.vaoEmu.set(id,vao);
                }
                return vao;
              };
              window.__gaiusGL.applyAttribBinding=function(attrib) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const index=attrib|0;
                const binding=vao.attribBindings.has(index) ? (vao.attribBindings.get(index)|0) : index;
                const format=vao.attribFormats.get(index);
                const vertexBuffer=vao.vertexBuffers.get(binding);
                if (!format || !vertexBuffer || !vertexBuffer.buffer) {
                  vao.attribHasBuffer.delete(index);
                  return;
                }
                const bufferObject=vertexBuffer.buffer ? this.buffers.get(vertexBuffer.buffer|0) : null;
                if (!bufferObject) {
                  vao.attribHasBuffer.delete(index);
                  return;
                }
                const previous=gl.getParameter(gl.ARRAY_BUFFER_BINDING);
                const offset=Number(vertexBuffer.offset || 0) + Number(format.relativeOffset || 0);
                const stride=vertexBuffer.stride|0;
                gl.bindBuffer(gl.ARRAY_BUFFER,bufferObject);
                vao.attribPointers.set(index,{
                  index:index,
                  size:format.size|0,
                  type:format.type|0,
                  normalized:!!format.normalized,
                  stride:stride,
                  offset:offset,
                  integer:!!format.integer,
                  buffer:vertexBuffer.buffer|0
                });
                const componentBytes=this.componentBytes(format.type);
                const aligned=(offset % componentBytes)===0 && (stride===0 || (stride % componentBytes)===0);
                if (aligned) {
                  if (format.integer) {
                    gl.vertexAttribIPointer(index,format.size|0,format.type|0,stride,offset);
                  } else {
                    gl.vertexAttribPointer(index,format.size|0,format.type|0,!!format.normalized,stride,offset);
                  }
                } else {
                  var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                  stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
                }
                vao.attribHasBuffer.add(index);
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
              window.__gaiusGL.ensureAlignedAttribs=function() {
                var gl=window.__gaiusWebGL;
                var vao=this.getVaoEmu();
                var groups=new Map();
                vao.enabledAttribs.forEach(function(attrib) {
                  var index=attrib|0;
                  var pointer=vao.attribPointers.get(index);
                  if (!pointer || !pointer.buffer || !vao.attribHasBuffer.has(index)) return;
                  var componentBytes=this.componentBytes(pointer.type);
                  var stride=pointer.stride|0;
                  var misaligned=(pointer.offset % componentBytes)!==0
                    || (stride!==0 && (stride % componentBytes)!==0);
                  if (!misaligned) return;
                  var group=groups.get(pointer.buffer|0);
                  if (!group) {
                    group=[];
                    groups.set(pointer.buffer|0,group);
                  }
                  group.push(pointer);
                }, this);
                if (!groups.size) return 0;
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                var aligned=0;
                var previousArray=gl.getParameter(gl.ARRAY_BUFFER_BINDING);
                groups.forEach(function(_misalignedPointers, sourceBuffer) {
                  var pointers=[];
                  vao.enabledAttribs.forEach(function(attrib) {
                    var pointer=vao.attribPointers.get(attrib|0);
                    if (pointer && (pointer.buffer|0)===(sourceBuffer|0) && vao.attribHasBuffer.has(attrib|0)) {
                      pointers.push(pointer);
                    }
                  });
                  pointers.sort(function(a,b){ return (a.index|0)-(b.index|0); });
                  var source=this.bufferBytes.get(sourceBuffer|0);
                  if (!source || !source.byteLength || !pointers.length) {
                    stats.alignedAttribMissingSource=(stats.alignedAttribMissingSource||0)+1;
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
                    return;
                  }
                  var version=this.bufferVersions.get(sourceBuffer|0)||0;
                  var key=(sourceBuffer|0)+':'+version+':'+vertexCount+':'+layoutKey;
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
                  }
                  aligned += pointers.length;
                }, this);
                gl.bindBuffer(gl.ARRAY_BUFFER,previousArray);
                if (aligned) {
                  stats.alignedAttribDraws=(stats.alignedAttribDraws||0)+1;
                  stats.alignedAttribPointers=(stats.alignedAttribPointers||0)+aligned;
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
              window.__gaiusGL.bindAttribPointerAtOffset=function(pointer, offset) {
                const gl=window.__gaiusWebGL;
                const bufferObject=this.buffers.get(pointer.buffer|0);
                if (!bufferObject) {
                  return false;
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
                const attribs=this.programAttribs.get(program);
                if (!program || !attribs || !attribs.length) return 0;
                const vao=this.getVaoEmu();
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
                  if (previousArray===null) previousArray=gl.getParameter(gl.ARRAY_BUFFER_BINDING);
                  const oldInteger=!!pointer.integer;
                  pointer.integer=expected;
                  if (this.bindAttribPointerAtOffset(pointer,Number(pointer.offset))) {
                    repaired++;
                    const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
                  gl.bindBuffer(gl.ARRAY_BUFFER,previousArray);
                }
                return repaired;
              };
              window.__gaiusGL.withBaseVertexAttribs=function(baseVertex, draw) {
                const gl=window.__gaiusWebGL;
                const vao=this.getVaoEmu();
                const shifted=[];
                const previousArray=gl.getParameter(gl.ARRAY_BUFFER_BINDING);
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
                    if (this.bindAttribPointerAtOffset(pointer,shiftedOffset)) {
                      shifted.push(pointer);
                    } else {
                      stats.baseVertexMissingBuffer=(stats.baseVertexMissingBuffer||0)+1;
                    }
                  }, this);
                  if (shifted.length) {
                    stats.baseVertexFallbackDraws=(stats.baseVertexFallbackDraws||0)+1;
                    stats.baseVertexShiftedAttribs=(stats.baseVertexShiftedAttribs||0)+shifted.length;
                    stats.baseVertexLast=Number(baseVertex)|0;
                  }
                  draw();
                } finally {
                  for (var i=0;i<shifted.length;i++) {
                    this.bindAttribPointerAtOffset(shifted[i],Number(shifted[i].offset));
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
                  const shifted=wasmHotpath.shiftIndices(type|0, source, start, length, base);
                  if (shifted && shifted.output) {
                    outputType=shifted.type|0;
                    output=shifted.output;
                    minIndex=shifted.min>>>0;
                    maxIndex=shifted.max>>>0;
                    stats.baseVertexIndexWasm=(stats.baseVertexIndexWasm||0)+1;
                    stats.baseVertexIndexWasmBytes=(stats.baseVertexIndexWasmBytes||0)+(shifted.bytes|0);
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
                    const shifted=Number(values[i]) + base;
                    if (shifted < 0 || shifted > 4294967295) {
                      stats.baseVertexIndexOutOfRange=(stats.baseVertexIndexOutOfRange||0)+1;
                      return null;
                    }
                    if (shifted > maxIndex) maxIndex=shifted;
                    if (shifted < minIndex) minIndex=shifted;
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
                const disabled=[];
                vao.enabledAttribs.forEach(function(attrib) {
                  const index=attrib|0;
                  if (!vao.attribHasBuffer.has(index)) {
                    gl.disableVertexAttribArray(index);
                    disabled.push(index);
                  }
                });
                var stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
                if (disabled.length) {
                  stats.attribGuardDraws=(stats.attribGuardDraws||0)+1;
                  stats.attribGuardDisabled=(stats.attribGuardDisabled||0)+disabled.length;
                  stats.attribGuardLast=disabled.slice(0,16);
                }
                this.ensureProgramAttribTypes();
                this.ensureAlignedAttribs();
                try {
                  this.recordDrawCall();
                  draw();
                } finally {
                  for (var i=0;i<disabled.length;i++) {
                    gl.enableVertexAttribArray(disabled[i]);
                  }
                }
              };
            }
            """)
    public static native void initialize();

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            window.__gaiusWebGL.enable(capability);
            """)
    public static native void enable(int capability);

    @JSBody(params = {"capability"}, script = """
            if (capability === 0x884F || capability === 0x8642) {
              return;
            }
            window.__gaiusWebGL.disable(capability);
            """)
    public static native void disable(int capability);

    @JSBody(params = {"red", "green", "blue", "alpha"},
            script = "window.__gaiusWebGL.clearColor(red,green,blue,alpha);")
    public static native void clearColor(float red, float green, float blue, float alpha);

    @JSBody(params = {"depth"}, script = "window.__gaiusWebGL.clearDepth(depth);")
    public static native void clearDepth(double depth);

    @JSBody(params = {"mask"}, script = "window.__gaiusWebGL.clear(mask);")
    public static native void clear(int mask);

    @JSBody(params = {"red", "green", "blue", "alpha"},
            script = "window.__gaiusWebGL.colorMask(red,green,blue,alpha);")
    public static native void colorMask(boolean red, boolean green, boolean blue, boolean alpha);

    @JSBody(params = {"func"}, script = "window.__gaiusWebGL.depthFunc(func);")
    public static native void depthFunc(int function);

    @JSBody(params = {"enabled"}, script = "window.__gaiusWebGL.depthMask(enabled);")
    public static native void depthMask(boolean enabled);

    @JSBody(params = {"mode", "first", "count"}, script = """
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withValidAttribs(function() {
              gl.drawArrays(mode,first,count);
            });
            """)
    public static native void drawArrays(int mode, int first, int count);

    @JSBody(params = {"mode", "count", "type", "offset"}, script = """
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withValidAttribs(function() {
              gl.drawElements(mode,count,type,Number(offset));
            });
            """)
    public static native void drawElements(int mode, int count, int type, long offset);

    @JSBody(script = "return window.__gaiusWebGL.getError()|0;")
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
            script = "window.__gaiusWebGL.viewport(x,y,width,height);")
    public static native void viewport(int x, int y, int width, int height);

    @JSBody(params = {"x", "y", "width", "height"},
            script = "window.__gaiusWebGL.scissor(x,y,width,height);")
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
            if (object) window.__gaiusWebGL.deleteTexture(object); state.textures.delete(texture);
            """)
    public static native void deleteTexture(int texture);

    @JSBody(params = {"target", "texture"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL;
            const webTarget=target===0x8C2A ? gl.TEXTURE_2D : target;
            const object=texture===0?null:state.textures.get(texture);
            gl.bindTexture(webTarget,object);
            const unit=state.activeTextureUnit || 0;
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
            state.bufferBytes.delete(buffer);
            state.bufferVersions.delete(buffer);
            state.bumpBufferVersion(buffer);
            state.vaoEmu.forEach(function(vao) {
              if ((vao.elementArrayBuffer|0)===(buffer|0)) vao.elementArrayBuffer=0;
              vao.attribPointers.forEach(function(pointer, attrib) {
                if ((pointer.buffer|0)===(buffer|0)) {
                  vao.attribHasBuffer.delete(attrib|0);
                  vao.attribPointers.delete(attrib|0);
                }
              });
              vao.vertexBuffers.forEach(function(vertexBuffer, binding) {
                if ((vertexBuffer.buffer|0)===(buffer|0)) vao.vertexBuffers.delete(binding|0);
              });
            });
            """)
    public static native void deleteBuffer(int buffer);

    @JSBody(params = {"target", "buffer"}, script = """
            const state=window.__gaiusGL;
            window.__gaiusWebGL.bindBuffer(target,buffer===0?null:state.buffers.get(buffer));
            state.boundBuffers.set(target,buffer);
            if (target===window.__gaiusWebGL.ELEMENT_ARRAY_BUFFER) {
              state.getVaoEmu().elementArrayBuffer=buffer|0;
            }
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
              state.shadowBufferData(buffer,null,actual);
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
              state.shadowBufferData(buffer,upload,actual);
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
              state.shadowBufferSubData(buffer,Number(offset),data);
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
              state.shadowBufferData(buffer,null,actual);
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
              state.shadowBufferData(buffer,upload,actual);
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
              state.shadowBufferSubData(buffer,Number(offset),data);
            }
            gl.bindBuffer(gl.COPY_WRITE_BUFFER,previous);
            """)
    private static native void namedBufferSubDataJs(int buffer, long offset, Int8Array data);

    public static ByteBuffer mapBufferRange(int target, long offset, long length, int access) {
        if (length < 0L || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported WebGL mapped buffer length: " + length);
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(target, new MappedBuffer(offset, buffer));
        return buffer;
    }

    public static boolean unmapBuffer(int target) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(target);
        if (mapped == null) {
            return true;
        }
        bufferSubDataJs(target, mapped.offset, allBytes(mapped.buffer));
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
        ByteBuffer byteBuffer = ByteBuffer.allocate((int) length).order(ByteOrder.nativeOrder());
        MAPPED_BUFFERS.put(namedBufferKey(buffer), new MappedBuffer(offset, byteBuffer));
        return byteBuffer;
    }

    public static boolean unmapNamedBuffer(int buffer) {
        MappedBuffer mapped = MAPPED_BUFFERS.remove(namedBufferKey(buffer));
        if (mapped == null) {
            return true;
        }
        namedBufferSubDataJs(buffer, mapped.offset, allBytes(mapped.buffer));
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
            """)
    public static native void linkProgram(int program);

    @JSBody(params = {"program"}, script = """
            const state=window.__gaiusGL;
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
            window.__gaiusGL.getVaoEmu().enabledAttribs.add(index|0);
            window.__gaiusWebGL.enableVertexAttribArray(index);
            """)
    public static native void enableVertexAttribArray(int index);

    @JSBody(params = {"index"}, script = """
            window.__gaiusGL.getVaoEmu().enabledAttribs.delete(index|0);
            window.__gaiusWebGL.disableVertexAttribArray(index);
            """)
    public static native void disableVertexAttribArray(int index);

    @JSBody(params = {"index", "size", "type", "normalized", "stride", "offset"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,vao=state.getVaoEmu();
            const buffer=state.boundBuffers.get(gl.ARRAY_BUFFER)|0;
            if (buffer) vao.attribHasBuffer.add(index|0);
            else vao.attribHasBuffer.delete(index|0);
            vao.attribPointers.set(index|0,{
              index:index|0,
              size:size|0,
              type:type|0,
              normalized:!!normalized,
              stride:stride|0,
              offset:Number(offset),
              integer:false,
              buffer:buffer|0
            });
            const componentBytes=state.componentBytes(type);
            const numericOffset=Number(offset);
            const numericStride=stride|0;
            const aligned=(numericOffset % componentBytes)===0
              && (numericStride===0 || (numericStride % componentBytes)===0);
            if (aligned) {
              gl.vertexAttribPointer(index,size,type,normalized,stride,numericOffset);
            } else {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
              stats.alignedAttribDeferredPointers=(stats.alignedAttribDeferredPointers||0)+1;
            }
            """)
    public static native void vertexAttribPointer(
            int index, int size, int type, boolean normalized, int stride, long offset);

    @JSBody(params = {"index", "size", "type", "stride", "offset"}, script = """
            const gl=window.__gaiusWebGL,state=window.__gaiusGL,vao=state.getVaoEmu();
            const buffer=state.boundBuffers.get(gl.ARRAY_BUFFER)|0;
            if (buffer) vao.attribHasBuffer.add(index|0);
            else vao.attribHasBuffer.delete(index|0);
            vao.attribPointers.set(index|0,{
              index:index|0,
              size:size|0,
              type:type|0,
              normalized:false,
              stride:stride|0,
              offset:Number(offset),
              integer:true,
              buffer:buffer|0
            });
            const componentBytes=state.componentBytes(type);
            const numericOffset=Number(offset);
            const numericStride=stride|0;
            const aligned=(numericOffset % componentBytes)===0
              && (numericStride===0 || (numericStride % componentBytes)===0);
            if (aligned) {
              gl.vertexAttribIPointer(index,size,type,stride,numericOffset);
            } else {
              const stats=window.__gaiusGLStats || (window.__gaiusGLStats={});
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
            if ((buffer|0)===0) {
              vao.vertexBuffers.delete(binding|0);
              vao.attribFormats.forEach(function(_format, attrib) {
                const attribBinding=vao.attribBindings.has(attrib) ? (vao.attribBindings.get(attrib)|0) : (attrib|0);
                if (attribBinding===(binding|0)) {
                  vao.attribHasBuffer.delete(attrib|0);
                }
              });
            } else {
              vao.vertexBuffers.set(binding|0,{
                buffer: buffer|0,
                offset: Number(offset),
                stride: stride|0
              });
            }
            state.applyVertexBinding(binding|0);
            """)
    public static native void bindVertexBuffer(int binding, int buffer, long offset, int stride);

    @JSBody(params = {"index", "binding"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            vao.attribBindings.set(index|0,binding|0);
            state.applyAttribBinding(index|0);
            """)
    public static native void vertexAttribBinding(int index, int binding);

    @JSBody(params = {"index", "size", "type", "normalized", "relativeOffset"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            vao.attribFormats.set(index|0,{
              size: size|0,
              type: type|0,
              normalized: !!normalized,
              relativeOffset: relativeOffset|0,
              integer: false
            });
            state.applyAttribBinding(index|0);
            """)
    public static native void vertexAttribFormat(
            int index, int size, int type, boolean normalized, int relativeOffset);

    @JSBody(params = {"index", "size", "type", "relativeOffset"}, script = """
            const state=window.__gaiusGL;
            const vao=state.getVaoEmu();
            vao.attribFormats.set(index|0,{
              size: size|0,
              type: type|0,
              normalized: false,
              relativeOffset: relativeOffset|0,
              integer: true
            });
            state.applyAttribBinding(index|0);
            """)
    public static native void vertexAttribIFormat(
            int index, int size, int type, int relativeOffset);

    @JSBody(script = """
            const state=window.__gaiusGL, id=state.next++;
            state.vaos.set(id,window.__gaiusWebGL.createVertexArray());
            state.vaoEmu.set(id,{
              attribBindings:new Map(),
              attribFormats:new Map(),
              attribPointers:new Map(),
              vertexBuffers:new Map(),
              enabledAttribs:new Set(),
              attribHasBuffer:new Set(),
              elementArrayBuffer:0
            });
            return id|0;
            """)
    public static native int genVertexArray();

    @JSBody(params = {"array"}, script = """
            const state=window.__gaiusGL;
            state.currentVaoId=array|0;
            window.__gaiusWebGL.bindVertexArray(array===0?null:state.vaos.get(array));
            """)
    public static native void bindVertexArray(int array);

    @JSBody(params = {"array"}, script = """
            const state=window.__gaiusGL, object=state.vaos.get(array);
            if (object) window.__gaiusWebGL.deleteVertexArray(object); state.vaos.delete(array);
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
            window.__gaiusWebGL.framebufferTexture2D(
              target,attachment,textureTarget,texture===0?null:window.__gaiusGL.textures.get(texture),level);
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
            if (state.framebufferBindings.draw===framebuffer) state.framebufferBindings.draw=0;
            if (state.framebufferBindings.read===framebuffer) state.framebufferBindings.read=0;
            """)
    public static native void deleteFramebuffer(int framebuffer);

    @JSBody(params = {
            "sourceX0", "sourceY0", "sourceX1", "sourceY1",
            "targetX0", "targetY0", "targetX1", "targetY1", "mask", "filter"
    }, script = """
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
            window.__gaiusGL.withValidAttribs(function() {
              gl.drawArraysInstanced(mode,first,count,instances);
            });
            """)
    public static native void drawArraysInstanced(int mode, int first, int count, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "instances"}, script = """
            const gl=window.__gaiusWebGL;
            window.__gaiusGL.withValidAttribs(function() {
              gl.drawElementsInstanced(mode,count,type,Number(offset),instances);
            });
            """)
    public static native void drawElementsInstanced(
            int mode, int count, int type, long offset, int instances);

    @JSBody(params = {"mode", "count", "type", "offset", "baseVertex"}, script = """
            window.__gaiusGL.withValidAttribs(function() {
              window.__gaiusGL.drawElementsWithBaseVertex(mode,count,type,offset,1,baseVertex);
            });
            """)
    public static native void drawElementsBaseVertex(
            int mode, int count, int type, long offset, int baseVertex);

    @JSBody(params = {"mode", "count", "type", "offset", "instances", "baseVertex"}, script = """
            window.__gaiusGL.withValidAttribs(function() {
              window.__gaiusGL.drawElementsWithBaseVertex(mode,count,type,offset,instances,baseVertex);
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
            if (targetBuffer && source) {
              const start=Number(sourceOffset);
              const end=start+Number(size);
              state.shadowBufferSubData(targetBuffer,Number(targetOffset),source.subarray(start,end));
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
              if (source) {
                const start=Number(sourceOffset);
                state.shadowBufferSubData(targetBuffer,Number(targetOffset),source.subarray(start,start+Number(size)));
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
        return value == null ? null : value.getClass().getName();
    }

    private static Int8Array bytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer copy = buffer.duplicate();
        return memoryBytes(buffer, copy.position(), copy.remaining());
    }

    private static Int8Array allBytes(ByteBuffer buffer) {
        return memoryBytes(buffer, 0, buffer.capacity());
    }

    private static Int8Array bytesSlice(ByteBuffer buffer, long offset, long length) {
        if (offset < 0L || length < 0L || offset + length > buffer.capacity() || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Unsupported WebGL mapped buffer flush range: " + offset + " + " + length);
        }
        return memoryBytes(buffer, (int) offset, (int) length);
    }

    private static Int8Array memoryBytes(ByteBuffer buffer, int offset, int length) {
        long address = MemoryUtil.memAddress0(buffer) + offset;
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = MemoryUtil.memGetByte(address + i);
        }
        return Int8Array.fromJavaArray(data);
    }

    private static Int8Array pointerBytes(long address, int length) {
        if (address == 0L) {
            return null;
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = MemoryUtil.memGetByte(address + i);
        }
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
        float[] data = new float[count];
        for (int i = 0; i < count; i++) {
            data[i] = MemoryUtil.memGetFloat(address + (long) i * 4L);
        }
        return Float32Array.fromJavaArray(data);
    }

    private static Int32Array pointerInts(long address, int count) {
        if (address == 0L) {
            return null;
        }
        int[] data = new int[count];
        for (int i = 0; i < count; i++) {
            data[i] = MemoryUtil.memGetInt(address + (long) i * 4L);
        }
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
