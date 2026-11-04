package io.dataease.localaccess;

import io.dataease.constant.StaticResourceConstants;
import io.dataease.localaccess.manage.LocalAccessManage;
import io.dataease.localaccess.manage.LocalBrandingManage;
import io.dataease.localaccess.security.LocalAccessContext;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.system.dao.auto.mapper.CoreSysSettingMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class LocalBrandingManageTest {
    @Test public void uploadPersistReadResetAndValidation() throws Exception {
        new io.dataease.utils.ModelUtils().setModelValue("standalone");
        new io.dataease.utils.IDUtils().setSnowFlake(new io.dataease.utils.SnowFlake(1,1));
        String original=StaticResourceConstants.APPEARANCE_DIR;
        var directory=Files.createTempDirectory("de-logo-test");
        var mapper=mock(CoreSysSettingMapper.class);var access=mock(LocalAccessManage.class);
        var rows=new ArrayList<CoreSysSetting>();
        when(access.enabled()).thenReturn(true);
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(i->new ArrayList<>(rows));
        when(mapper.delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(i->{rows.clear();return 1;});
        when(mapper.insert(any(CoreSysSetting.class))).thenAnswer(i->{rows.add(i.getArgument(0));return 1;});
        var manage=new LocalBrandingManage(mapper,access);
        try {
            StaticResourceConstants.APPEARANCE_DIR=directory.toString();
            LocalAccessContext.set(new LocalAccessContext.Identity(1,"admin","Admin",true,null,null,null,null,Set.of()));
            assertEquals("",manage.get().logoUrl());
            var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(120,40,BufferedImage.TYPE_INT_ARGB),"png",bytes);
            var file=new MockMultipartFile("file","logo.png","image/png",bytes.toByteArray());
            assertTrue(manage.upload(file).logoUrl().startsWith("/localAccess/branding/image?v="));
            assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(manage.image())));
            assertNotNull(new LocalBrandingManage(mapper,access).image());
            assertThrows(IllegalArgumentException.class,()->manage.upload(new MockMultipartFile("file","bad.png","image/png","<svg/>".getBytes())));
            assertThrows(IllegalArgumentException.class,()->manage.upload(new MockMultipartFile("file","large.png","image/png",new byte[2*1024*1024+1])));
            var wide=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3079,749,BufferedImage.TYPE_INT_ARGB),"png",wide);
            manage.upload(new MockMultipartFile("file","wide.png","image/png",wide.toByteArray()));
            assertEquals(3079,ImageIO.read(new java.io.ByteArrayInputStream(manage.image())).getWidth());
            var oversized=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4097,1,BufferedImage.TYPE_INT_ARGB),"png",oversized);
            assertThrows(IllegalArgumentException.class,()->manage.upload(new MockMultipartFile("file","oversized.png","image/png",oversized.toByteArray())));
            LocalAccessContext.set(new LocalAccessContext.Identity(2,"user","User",false,1L,"a","A","user",Set.of("dashboard")));
            assertThrows(SecurityException.class,()->manage.upload(file));
            assertThrows(SecurityException.class,manage::reset);
            LocalAccessContext.clear();assertNotNull(manage.image());
            LocalAccessContext.set(new LocalAccessContext.Identity(1,"admin","Admin",true,null,null,null,null,Set.of()));
            manage.reset();assertEquals("",manage.get().logoUrl());assertNull(manage.image());
        } finally {
            LocalAccessContext.clear();StaticResourceConstants.APPEARANCE_DIR=original;
            try(var files=Files.walk(directory)){for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}
        }
    }
}
