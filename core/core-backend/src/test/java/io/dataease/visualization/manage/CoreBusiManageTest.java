package io.dataease.visualization.manage;

import io.dataease.dataset.manage.DatasetGroupManage;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.localaccess.security.LocalAccessContext;
import io.dataease.model.BusiNodeRequest;
import org.junit.After;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CoreBusiManageTest {
    @After public void cleanup() { LocalAccessContext.clear(); }

    @Test public void adminWithoutTenantLoadsAllHomepagePermissions() {
        var service = new CoreBusiManage();
        var visual = mock(CoreVisualizationManage.class);
        var dataset = mock(DatasetGroupManage.class);
        var datasource = mock(DataSourceManage.class);
        ReflectionTestUtils.setField(service, "coreVisualizationManage", visual);
        ReflectionTestUtils.setField(service, "datasetGroupManage", dataset);
        ReflectionTestUtils.setField(service, "dataSourceManage", datasource);
        var request = new BusiNodeRequest();
        var requests = Map.of("dashboard", request, "dataV", request, "dataset", request, "datasource", request);
        LocalAccessContext.set(new LocalAccessContext.Identity(1L, "admin", "Admin", true, null, null, null, "admin", Set.of()));
        assertEquals(requests.keySet(), service.interactiveTree(requests).keySet());
        verify(visual, times(2)).treeForCurrentIdentity(request);
        verify(dataset).treeForCurrentIdentity(request);
        verify(datasource).treeForCurrentIdentity(request);
        clearInvocations(visual, dataset, datasource);
        LocalAccessContext.set(new LocalAccessContext.Identity(2L, "user", "User", false, null, null, null, "user", Set.of("dataset")));
        assertTrue(service.interactiveTree(requests).isEmpty());
        verifyNoInteractions(visual, dataset, datasource);
        LocalAccessContext.set(new LocalAccessContext.Identity(2L, "user", "User", false, 2L, "tenant", "Tenant", "user", Set.of("dataset")));
        assertEquals(Set.of("dataset"), service.interactiveTree(requests).keySet());
        verify(dataset).treeForCurrentIdentity(request);
        verifyNoInteractions(visual, datasource);
    }
}
