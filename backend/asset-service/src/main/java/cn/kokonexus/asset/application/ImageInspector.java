package cn.kokonexus.asset.application;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** asset-service：ImageInspector 领域类型；字段单位、状态及可空性见各属性说明。 */
@Component
public class ImageInspector {

    static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    static final int MAX_EDGE = 4096;
    static final long MAX_PIXELS = 8_000_000L;

    public InspectedImage inspect(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("图片不能为空且不能超过 5 MiB");
        }
        String filename = file.getOriginalFilename();
        String extension = extension(filename);
        try {
            byte[] input = file.getBytes();
            if (input.length == 0 || input.length > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("图片不能为空且不能超过 5 MiB");
            }
            String format = detectFormat(input);
            String contentType = format.equals("jpeg") ? "image/jpeg" : "image/png";
            String expectedExtension = format.equals("jpeg") ? "jpg" : "png";
            if (
                !(extension.equals(expectedExtension) || (format.equals("jpeg") && extension.equals("jpeg"))) ||
                !contentType.equalsIgnoreCase(file.getContentType())
            ) {
                throw new IllegalArgumentException("图片扩展名、MIME 类型与实际内容不一致");
            }
            try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IllegalArgumentException("图片内容无法解析");
                ImageReader reader = readers.next();
                try {
                    if (!reader.getFormatName().equalsIgnoreCase(format)) {
                        throw new IllegalArgumentException("图片内容签名与编码不一致");
                    }
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (
                        width < 1 ||
                        height < 1 ||
                        width > MAX_EDGE ||
                        height > MAX_EDGE ||
                        (long) width * height > MAX_PIXELS
                    ) {
                        throw new IllegalArgumentException("图片尺寸超出限制");
                    }
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                        throw new IllegalArgumentException("图片内容无法解析");
                    }
                    byte[] normalized = encode(decoded, format);
                    if (normalized.length > MAX_UPLOAD_BYTES) {
                        throw new IllegalArgumentException("处理后的图片超过 5 MiB");
                    }
                    return new InspectedImage(
                        normalized,
                        contentType,
                        expectedExtension,
                        width,
                        height,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized))
                    );
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片内容无法解析", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String extension(String filename) {
        if (filename == null || filename.length() > 255 || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("图片文件名无效");
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 1) throw new IllegalArgumentException("图片文件名无效");
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String detectFormat(byte[] bytes) {
        if (
            bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff
        ) return "jpeg";
        int[] png = { 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
        if (bytes.length >= png.length) {
            boolean matches = true;
            for (int i = 0; i < png.length; i++) matches &= (bytes[i] & 0xff) == png[i];
            if (matches) return "png";
        }
        throw new IllegalArgumentException("仅支持真实的 JPEG 或 PNG 图片");
    }

    byte[] encode(BufferedImage decoded, String format) throws IOException {
        BufferedImage safe = new BufferedImage(
            decoded.getWidth(),
            decoded.getHeight(),
            format.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = safe.createGraphics();
        try {
            graphics.drawImage(decoded, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var stream = new LimitedImageOutputStream(output)) {
            if (!ImageIO.write(safe, format, stream)) throw new IllegalArgumentException("图片编码不可用");
            stream.flush();
        }
        return output.toByteArray();
    }

    private static final class LimitedImageOutputStream extends MemoryCacheImageOutputStream {

        private LimitedImageOutputStream(ByteArrayOutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            requireRoom(1);
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireRoom(length);
            super.write(bytes, offset, length);
        }

        @Override
        public void seek(long position) throws IOException {
            if (position > MAX_UPLOAD_BYTES) throw new IOException("处理后的图片超过 5 MiB");
            super.seek(position);
        }

        private void requireRoom(int length) throws IOException {
            if (length < 0 || getStreamPosition() + length > MAX_UPLOAD_BYTES) {
                throw new IOException("处理后的图片超过 5 MiB");
            }
        }
    }

    /** asset-service：InspectedImage 领域类型；字段单位、状态及可空性见各属性说明。 */
    public record InspectedImage(
        @io.swagger.v3.oas.annotations.media.Schema(description = "归一化媒体内容字节数组，禁止写日志") byte[] bytes,
        @io.swagger.v3.oas.annotations.media.Schema(description = "归一化媒体 MIME 类型") String contentType,
        @io.swagger.v3.oas.annotations.media.Schema(description = "受支持的媒体文件扩展名") String extension,
        @io.swagger.v3.oas.annotations.media.Schema(description = "图像像素宽度") int width,
        @io.swagger.v3.oas.annotations.media.Schema(description = "图像像素高度") int height,
        @io.swagger.v3.oas.annotations.media.Schema(description = "归一化内容 SHA-256 摘要") String sha256
    ) {}
}
