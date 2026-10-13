use flate2::{write::ZlibEncoder, Compression};
use gaius_png::decode;
use std::io::Write;

struct Image {
    width: usize,
    height: usize,
    depth: u8,
    color: u8,
    samples: Vec<u16>,
    extras: Vec<([u8; 4], Vec<u8>)>,
}

fn chunk(output: &mut Vec<u8>, kind: &[u8; 4], bytes: &[u8]) {
    output.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    let start = output.len();
    output.extend_from_slice(kind);
    output.extend_from_slice(bytes);
    output.extend_from_slice(&crc32fast::hash(&output[start..]).to_be_bytes());
}

fn pack(samples: &[u16], depth: u8) -> Vec<u8> {
    if depth == 16 {
        return samples.iter().flat_map(|sample| sample.to_be_bytes()).collect();
    }
    let mut packed = vec![0; (samples.len() * depth as usize).div_ceil(8)];
    for (index, &sample) in samples.iter().enumerate() {
        let offset = index * depth as usize;
        packed[offset / 8] |= (sample as u8) << (8 - depth as usize - offset % 8);
    }
    packed
}

fn paeth(left: i32, above: i32, corner: i32) -> u8 {
    let prediction = left + above - corner;
    let distances = [
        (prediction - left).abs(),
        (prediction - above).abs(),
        (prediction - corner).abs(),
    ];
    if distances[0] <= distances[1] && distances[0] <= distances[2] {
        left as u8
    } else if distances[1] <= distances[2] {
        above as u8
    } else {
        corner as u8
    }
}

fn encode(image: &Image, interlaced: bool, filter: u8) -> Vec<u8> {
    let channels = match image.color {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        6 => 4,
        _ => unreachable!(),
    };
    let steps: &[(usize, usize, usize, usize)] = if interlaced {
        &[
            (0, 0, 8, 8),
            (4, 0, 8, 8),
            (0, 4, 4, 8),
            (2, 0, 4, 4),
            (0, 2, 2, 4),
            (1, 0, 2, 2),
            (0, 1, 1, 2),
        ]
    } else {
        &[(0, 0, 1, 1)]
    };
    let mut raw = Vec::new();
    let bpp = (channels * image.depth as usize).div_ceil(8).max(1);
    for &(first_x, first_y, step_x, step_y) in steps {
        if first_x >= image.width || first_y >= image.height {
            continue;
        }
        let mut previous = Vec::<u8>::new();
        for row_y in (first_y..image.height).step_by(step_y) {
            let mut samples = Vec::new();
            for column_x in (first_x..image.width).step_by(step_x) {
                let start = (row_y * image.width + column_x) * channels;
                samples.extend_from_slice(&image.samples[start..start + channels]);
            }
            let row = pack(&samples, image.depth);
            raw.push(filter);
            for (index, &sample) in row.iter().enumerate() {
                let left = if index >= bpp { row[index - bpp] } else { 0 };
                let above = previous.get(index).copied().unwrap_or(0);
                let corner = if index >= bpp {
                    previous.get(index - bpp).copied().unwrap_or(0)
                } else {
                    0
                };
                let predictor = match filter {
                    0 => 0,
                    1 => left,
                    2 => above,
                    3 => ((left as u16 + above as u16) / 2) as u8,
                    4 => paeth(left as i32, above as i32, corner as i32),
                    _ => 0,
                };
                raw.push(sample.wrapping_sub(predictor));
            }
            previous = row;
        }
    }
    let mut zlib = ZlibEncoder::new(Vec::new(), Compression::default());
    zlib.write_all(&raw).unwrap();
    let mut output = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut header = (image.width as u32).to_be_bytes().to_vec();
    header.extend_from_slice(&(image.height as u32).to_be_bytes());
    header.extend_from_slice(&[image.depth, image.color, 0, 0, u8::from(interlaced)]);
    chunk(&mut output, b"IHDR", &header);
    for (kind, value) in &image.extras {
        chunk(&mut output, kind, value);
    }
    let compressed = zlib.finish().unwrap();
    for part in compressed.chunks(7) {
        chunk(&mut output, b"IDAT", part);
    }
    chunk(&mut output, b"IEND", &[]);
    output
}

fn convert(rgba: &[u8], channels: u32) -> Vec<u8> {
    let mut expected = Vec::new();
    for sample in rgba.as_chunks::<4>().0 {
        let gray = ((sample[0] as u32 * 299 + sample[1] as u32 * 587 + sample[2] as u32 * 114 + 500) / 1000) as u8;
        match channels {
            1 => expected.push(gray),
            2 => expected.extend_from_slice(&[gray, sample[3]]),
            3 => expected.extend_from_slice(&sample[..3]),
            4 => expected.extend_from_slice(sample),
            _ => unreachable!(),
        }
    }
    expected
}

#[test]
fn every_legal_type_depth_filter_and_channel() {
    for color in [0, 2, 3, 4, 6] {
        let depths: &[u8] = match color {
            0 => &[1, 2, 4, 8, 16],
            3 => &[1, 2, 4, 8],
            _ => &[8, 16],
        };
        for &depth in depths {
            let channels = match color {
                0 | 3 => 1,
                2 => 3,
                4 => 2,
                _ => 4,
            };
            let maximum = if depth == 16 { 65535 } else { (1 << depth) - 1 };
            let mut image = Image {
                width: 7,
                height: 5,
                depth,
                color,
                samples: Vec::new(),
                extras: Vec::new(),
            };
            let palette: Vec<u8> = (0..=maximum.min(255))
                .flat_map(|index| [index as u8, 255 - index as u8, (index * 13) as u8])
                .collect();
            if color == 3 {
                image.extras.push((*b"PLTE", palette.clone()));
            }
            let mut rgba = Vec::new();
            for pixel in 0..35 {
                let samples: Vec<u16> = (0..channels)
                    .map(|component| ((pixel * 101 + component * 37) & maximum) as u16)
                    .collect();
                let scale = |sample: u16| {
                    if depth == 16 {
                        (sample >> 8) as u8
                    } else {
                        (sample as usize * 255 / maximum) as u8
                    }
                };
                let gray = scale(samples[0]);
                let expected = match color {
                    0 => [gray, gray, gray, 255],
                    2 => [gray, scale(samples[1]), scale(samples[2]), 255],
                    3 => {
                        let start = samples[0] as usize * 3;
                        [palette[start], palette[start + 1], palette[start + 2], 255]
                    }
                    4 => [gray, gray, gray, scale(samples[1])],
                    _ => [gray, scale(samples[1]), scale(samples[2]), scale(samples[3])],
                };
                image.samples.extend_from_slice(&samples);
                rgba.extend_from_slice(&expected);
            }
            let source_channels = if color == 3 { 3 } else { channels as u32 };
            for interlaced in [false, true] {
                for filter in 0..5 {
                    let encoded = encode(&image, interlaced, filter);
                    for desired in 0..5 {
                        let actual = decode(&encoded, desired).unwrap();
                        let output_channels = if desired == 0 { source_channels } else { desired };
                        assert_eq!(actual.source_channels, source_channels);
                        assert_eq!(actual.output_channels, output_channels);
                        assert_eq!(
                            actual.pixels,
                            convert(&rgba, output_channels),
                            "color={color} depth={depth} filter={filter} interlace={interlaced} desired={desired}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn transparency_compares_full_samples_before_stripping_and_keeps_source_channels() {
    for color in [0, 2] {
        let channels = if color == 0 { 1 } else { 3 };
        let samples: Vec<u16> = (0..channels).map(|component| 0x1234 + component * 0x1010).collect();
        let mut image = Image {
            width: 2,
            height: 1,
            depth: 16,
            color,
            samples: samples.clone(),
            extras: vec![(
                *b"tRNS",
                samples.iter().flat_map(|sample| sample.to_be_bytes()).collect(),
            )],
        };
        image.samples.extend(samples.iter().map(|sample| sample + 1));
        let encoded = encode(&image, false, 0);
        let actual = decode(&encoded, 4).unwrap();
        assert_eq!(actual.source_channels, channels as u32);
        assert_eq!(actual.pixels[3], 0);
        assert_eq!(actual.pixels[7], 255);
        assert_eq!(&actual.pixels[..3], &actual.pixels[4..7]);
        assert_eq!(decode(&encoded, 0).unwrap().pixels.len(), 2 * channels as usize);
    }
}

#[test]
fn palette_transparency_gamma_and_partial_alpha() {
    let mut image = Image {
        width: 4,
        height: 1,
        depth: 2,
        color: 3,
        samples: vec![0, 1, 2, 3],
        extras: vec![
            (*b"PLTE", vec![255, 0, 0, 0, 255, 0, 0, 0, 255, 80, 80, 80]),
            (*b"tRNS", vec![0, 127]),
        ],
    };
    let plain = decode(&encode(&image, false, 0), 0).unwrap();
    assert_eq!(plain.source_channels, 4);
    assert_eq!(
        plain.pixels,
        vec![255, 0, 0, 0, 0, 255, 0, 127, 0, 0, 255, 255, 80, 80, 80, 255]
    );
    image.extras.push((*b"gAMA", 45455u32.to_be_bytes().to_vec()));
    image.extras.push((*b"sRGB", vec![0]));
    assert_eq!(decode(&encode(&image, false, 4), 0).unwrap(), plain);
    assert_eq!(
        decode(&encode(&image, false, 0), 1).unwrap().pixels,
        vec![76, 150, 29, 80]
    );
}

#[test]
fn tiny_adam7_images_skip_empty_passes() {
    for (width, height) in [(1, 1), (1, 3), (2, 1), (3, 2), (9, 7)] {
        let image = Image {
            width,
            height,
            depth: 1,
            color: 0,
            samples: (0..width * height).map(|index| (index % 2) as u16).collect(),
            extras: Vec::new(),
        };
        assert_eq!(
            decode(&encode(&image, true, 4), 4).unwrap(),
            decode(&encode(&image, false, 0), 4).unwrap()
        );
    }
}

#[test]
fn rejects_corruption_truncation_and_resource_bombs() {
    let image = Image {
        width: 1,
        height: 1,
        depth: 8,
        color: 6,
        samples: vec![1, 2, 3, 4],
        extras: Vec::new(),
    };
    let encoded = encode(&image, false, 0);
    assert!(decode(&encoded, 5).is_err());
    for end in 0..encoded.len() - 12 {
        assert!(decode(&encoded[..end], 4).is_err(), "end={end}");
    }
    let mut bad_crc = encoded.clone();
    bad_crc[29] ^= 1;
    assert!(decode(&bad_crc, 4).is_err());
    let mut oversized = encoded.clone();
    oversized[16..20].copy_from_slice(&u32::MAX.to_be_bytes());
    assert!(decode(&oversized, 4).is_err());
    assert!(decode(&encode(&image, false, 5), 4).is_err());
}
