use std::io::Cursor;

pub const MAX_IMAGE_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_DIMENSION: u32 = 16_384;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedPng {
    pub width: u32,
    pub height: u32,
    pub source_channels: u32,
    pub output_channels: u32,
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decline(pub String);

impl std::fmt::Display for Decline {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for Decline {}

fn decline(message: impl Into<String>) -> Decline {
    Decline(message.into())
}

pub fn decode(encoded: &[u8], desired_channels: u32) -> Result<DecodedPng, Decline> {
    if desired_channels > 4 {
        return Err(decline("unsupported desired channel count"));
    }
    if encoded.len() > MAX_IMAGE_BYTES {
        return Err(decline("encoded PNG exceeds the decode budget"));
    }
    if encoded.len() < 33 || &encoded[..8] != b"\x89PNG\r\n\x1a\n" || &encoded[12..16] != b"IHDR" {
        return Err(decline("not a PNG image"));
    }
    let width = u32::from_be_bytes(encoded[16..20].try_into().unwrap());
    let height = u32::from_be_bytes(encoded[20..24].try_into().unwrap());
    let rgba_size = (width as usize)
        .checked_mul(height as usize)
        .and_then(|count| count.checked_mul(4))
        .filter(|&count| count <= MAX_IMAGE_BYTES)
        .ok_or_else(|| decline("decoded PNG exceeds the decode budget"))?;
    if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(decline("PNG dimensions exceed the decode budget"));
    }
    let mut decoder = png::Decoder::new(Cursor::new(encoded));
    decoder.set_limits(png::Limits { bytes: MAX_IMAGE_BYTES });
    decoder.set_ignore_text_chunk(true);
    decoder.set_ignore_iccp_chunk(true);
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().map_err(|error| decline(error.to_string()))?;
    let source_channels = match reader.info().color_type {
        png::ColorType::Grayscale => 1,
        png::ColorType::Rgb => 3,
        png::ColorType::Indexed => {
            if reader.info().trns.is_some() {
                4
            } else {
                3
            }
        }
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Rgba => 4,
    };
    let output_channels = if desired_channels == 0 {
        source_channels
    } else {
        desired_channels
    };
    let buffer_size = reader
        .output_buffer_size()
        .filter(|&count| count <= MAX_IMAGE_BYTES)
        .ok_or_else(|| decline("PNG frame exceeds the decode budget"))?;
    let mut decoded = Vec::new();
    decoded
        .try_reserve_exact(buffer_size)
        .map_err(|_| decline("cannot allocate PNG frame"))?;
    decoded.resize(buffer_size, 0);
    let frame = reader
        .next_frame(&mut decoded)
        .map_err(|error| decline(error.to_string()))?;
    if frame.width != width || frame.height != height || frame.bit_depth != png::BitDepth::Eight {
        return Err(decline("unexpected decoded PNG layout"));
    }
    decoded.truncate(frame.buffer_size());
    let input_channels = frame.color_type.samples();
    let pixels = if input_channels == output_channels as usize {
        decoded
    } else {
        let output_size = rgba_size / 4 * output_channels as usize;
        let mut pixels = Vec::new();
        pixels
            .try_reserve_exact(output_size)
            .map_err(|_| decline("cannot allocate PNG pixels"))?;
        for sample in decoded.chunks_exact(input_channels) {
            let (red, green, blue, alpha) = match frame.color_type {
                png::ColorType::Grayscale => (sample[0], sample[0], sample[0], 255),
                png::ColorType::GrayscaleAlpha => (sample[0], sample[0], sample[0], sample[1]),
                png::ColorType::Rgb => (sample[0], sample[1], sample[2], 255),
                png::ColorType::Rgba => (sample[0], sample[1], sample[2], sample[3]),
                png::ColorType::Indexed => return Err(decline("unexpanded PNG palette")),
            };
            match output_channels {
                1 | 2 => {
                    let gray = (u32::from(red) * 299 + u32::from(green) * 587 + u32::from(blue) * 114 + 500) / 1000;
                    pixels.push(gray as u8);
                    if output_channels == 2 {
                        pixels.push(alpha);
                    }
                }
                3 => pixels.extend_from_slice(&[red, green, blue]),
                4 => pixels.extend_from_slice(&[red, green, blue, alpha]),
                _ => unreachable!(),
            }
        }
        pixels
    };
    if pixels.len() != rgba_size / 4 * output_channels as usize {
        return Err(decline("incomplete PNG frame"));
    }
    Ok(DecodedPng {
        width,
        height,
        source_channels,
        output_channels,
        pixels,
    })
}
