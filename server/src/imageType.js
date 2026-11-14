/**
 * 按文件头判断图片类型。
 *
 * 相册里的图片可能是 PNG / JPEG / WebP / HEIC，
 * 如果一律按 image/jpeg 返回，浏览器可能拒绝渲染（尤其 PNG 被标成 JPEG 时）。
 */
export function detectImageType(buf) {
  if (!buf || buf.length < 12) return "application/octet-stream";

  // JPEG: FF D8 FF
  if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return "image/jpeg";

  // PNG: 89 50 4E 47 0D 0A 1A 0A
  if (
    buf[0] === 0x89 &&
    buf[1] === 0x50 &&
    buf[2] === 0x4e &&
    buf[3] === 0x47 &&
    buf[4] === 0x0d &&
    buf[5] === 0x0a &&
    buf[6] === 0x1a &&
    buf[7] === 0x0a
  ) {
    return "image/png";
  }

  // GIF: "GIF8"
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46 && buf[3] === 0x38) return "image/gif";

  // WebP: "RIFF"...."WEBP"
  if (
    buf.toString("ascii", 0, 4) === "RIFF" &&
    buf.toString("ascii", 8, 12) === "WEBP"
  ) {
    return "image/webp";
  }

  // BMP: "BM"
  if (buf[0] === 0x42 && buf[1] === 0x4d) return "image/bmp";

  // HEIC / HEIF: ftyp 后第 8 字节起常见 heic/heix/mif1
  if (buf.toString("ascii", 4, 8) === "ftyp") {
    const brand = buf.toString("ascii", 8, 12).toLowerCase();
    if (["heic", "heix", "hevc", "mif1", "msf1"].includes(brand)) return "image/heic";
  }

  return "application/octet-stream";
}

/** 给照片元数据补上真实的扩展名与 MIME，供下载/展示使用。 */
export function sniffPhoto(buf) {
  const mime = detectImageType(buf);
  const ext =
    {
      "image/jpeg": "jpg",
      "image/png": "png",
      "image/gif": "gif",
      "image/webp": "webp",
      "image/bmp": "bmp",
      "image/heic": "heic",
    }[mime] || "bin";
  return { mime, ext };
}
