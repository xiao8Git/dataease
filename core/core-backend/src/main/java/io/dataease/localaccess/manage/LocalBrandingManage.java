package io.dataease.localaccess.manage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.api.localaccess.LocalAccessApi.Branding;
import io.dataease.constant.StaticResourceConstants;
import io.dataease.localaccess.security.LocalAccessContext;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.dao.auto.mapper.CoreSysSettingMapper;
import io.dataease.utils.IDUtils;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalBrandingManage {
    private static final String KEY = "localAccess.logo";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final CoreSysSettingMapper mapper;
    private final LocalAccessManage access;
    public LocalBrandingManage(CoreSysSettingMapper mapper, LocalAccessManage access) { this.mapper = mapper; this.access = access; }
    private QueryWrapper<CoreSysSetting> query() { return new QueryWrapper<CoreSysSetting>().eq("pkey", KEY); }
    private Path directory() { return Path.of(StaticResourceConstants.APPEARANCE_DIR, "local-branding"); }
    private String filename() {
        if (!access.enabled()) return null;
        var settings = mapper.selectList(query());
        if (settings.isEmpty()) return null;
        String value = settings.get(0).getPval();
        return value != null && value.matches("[a-f0-9]{64}\\.png") ? value : null;
    }
    private void requireAdmin() {
        if (!access.enabled() || !LocalAccessContext.require().admin()) throw new SecurityException("仅平台管理员可修改 Logo");
    }
    public Branding get() {
        String file = filename();
        return new Branding(file == null ? "" : "/localAccess/branding/image?v=" + file);
    }
    public byte[] image() throws java.io.IOException {
        String file = filename();
        return file == null ? null : Files.readAllBytes(directory().resolve(file));
    }
    @Transactional
    public synchronized Branding upload(MultipartFile file) throws java.io.IOException {
        requireAdmin();
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("请选择不超过 2MB 的 PNG 或 JPG 图片");
        byte[] encoded;
        try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("请选择 PNG 或 JPG 图片");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg")) || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096)
                    throw new IllegalArgumentException("Logo 仅支持 PNG/JPG，尺寸不能超过 4096×4096");
                var output = new ByteArrayOutputStream();
                ImageIO.write(reader.read(0), "png", output); encoded = output.toByteArray();
            } finally { reader.dispose(); }
        }
        if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("Logo 图片过大，请压缩后重试");
        String name;
        try { name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)) + ".png"; }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Files.createDirectories(directory());
        Files.write(directory().resolve(name), encoded);
        mapper.delete(query());
        var setting = new CoreSysSetting(); setting.setId(IDUtils.snowID()); setting.setPkey(KEY); setting.setPval(name); setting.setType("localAccess"); setting.setSort(0);
        mapper.insert(setting);
        return get();
    }
    @Transactional
    public synchronized void reset() { requireAdmin(); mapper.delete(query()); }
}
