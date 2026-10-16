package io.dataease.exportCenter.manage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.exportCenter.dao.ext.mapper.ExportTaskExtMapper;
import io.dataease.license.utils.LicenseUtil;
import io.dataease.model.ExportTaskDTO;
import io.dataease.utils.AuthUtils;
import io.dataease.utils.CommonBeanFactory;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class CommunityExportListTest {
    @Test
    public void communityListsOwnedTasksWithoutOrganizationExtension() {
        checkList(false);
    }

    @Test
    public void licensedListRetainsOrganizationExtension() {
        checkList(true);
    }

    @Test
    public void communityChartPathUsesLocalFoldersEvenWithExtensionInstalled() {
        new io.dataease.license.utils.CommonBeanFactory().setApplicationContext(
                new org.springframework.context.support.StaticApplicationContext());
        ExportCenterManage manage = new ExportCenterManage();
        var mapper = mock(io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoMapper.class);
        ReflectionTestUtils.setField(manage, "visualizationInfoMapper", mapper);
        var chart = new io.dataease.visualization.dao.auto.entity.DataVisualizationInfo();
        chart.setId(2L);
        chart.setPid(1L);
        var folder = new io.dataease.visualization.dao.auto.entity.DataVisualizationInfo();
        folder.setId(1L);
        folder.setPid(1L);
        when(mapper.selectById(2L)).thenReturn(chart);
        when(mapper.selectById(1L)).thenReturn(folder);
        try (MockedStatic<LicenseUtil> license = mockStatic(LicenseUtil.class);
             MockedStatic<CommonBeanFactory> beans = mockStatic(CommonBeanFactory.class)) {
            license.when(LicenseUtil::licenseValid).thenReturn(false);
            assertEquals(List.of(folder, chart), manage.getParents(2L));
            beans.verifyNoInteractions();
        }
    }

    private void checkList(boolean licensed) {
        new io.dataease.license.utils.CommonBeanFactory().setApplicationContext(
                new org.springframework.context.support.StaticApplicationContext());
        ExportCenterManage manage = spy(new ExportCenterManage());
        ExportCenterManage extension = mock(ExportCenterManage.class);
        ExportTaskExtMapper mapper = mock(ExportTaskExtMapper.class);
        ReflectionTestUtils.setField(manage, "exportTaskExtMapper", mapper);
        ExportTaskDTO task = new ExportTaskDTO();
        task.setExportStatus("SUCCESS");
        Page<ExportTaskDTO> page = new Page<>(1, 10);
        page.setRecords(List.of(task));
        page.setTotal(1);
        when(mapper.pager(any(), any())).thenAnswer(invocation -> {
            QueryWrapper<?> query = invocation.getArgument(1);
            assertTrue(query.getSqlSegment().contains("user_id"));
            assertTrue(query.getParamNameValuePairs().containsValue(123L));
            return page;
        });
        doNothing().when(manage).setExportFromAbsName(task);
        TokenUserBO user = new TokenUserBO();
        user.setUserId(123L);
        try (MockedStatic<AuthUtils> auth = mockStatic(AuthUtils.class);
             MockedStatic<LicenseUtil> license = mockStatic(LicenseUtil.class);
             MockedStatic<CommonBeanFactory> beans = mockStatic(CommonBeanFactory.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            license.when(LicenseUtil::licenseValid).thenReturn(licensed);
            beans.when(() -> CommonBeanFactory.getBean(ExportCenterManage.class)).thenReturn(extension);
            assertEquals(1, manage.pager(new Page<>(1, 10), "SUCCESS").getRecords().size());
            verify(manage).setExportFromAbsName(task);
            if (licensed) {
                verify(extension).setOrg(task);
            } else {
                verifyNoInteractions(extension);
                beans.verifyNoInteractions();
            }
        }
    }
}
