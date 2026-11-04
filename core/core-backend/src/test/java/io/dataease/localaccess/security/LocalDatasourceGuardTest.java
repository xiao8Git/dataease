package io.dataease.localaccess.security;

import io.dataease.api.dataset.dto.PreviewSqlDTO;
import io.dataease.api.ds.vo.BusiDsRequest;
import io.dataease.datasource.dao.auto.entity.CoreDatasource;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.extensions.datasource.dto.DatasetTableDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldRequest;
import io.dataease.localaccess.manage.LocalAccessManage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LocalDatasourceGuardTest {
    private LocalAccessManage manage;
    private DataSourceManage sources;
    private LocalDatasourceGuard guard;
    private ProceedingJoinPoint point;
    @Before public void setup() {
        manage = mock(LocalAccessManage.class); sources = mock(DataSourceManage.class);
        guard = new LocalDatasourceGuard(manage,sources); point = mock(ProceedingJoinPoint.class);
        LocalAccessContext.set(new LocalAccessContext.Identity(1,"test","Test",false,1L,"a","Tenant","user",Set.of("dataset")));
    }
    @After public void cleanup() { LocalAccessContext.clear(); }
    @Test public void forbiddenTableMetadataNeverReachesCachedHandler() throws Throwable {
        var table = new DatasetTableDTO();table.setDatasourceId(702L);
        when(point.getArgs()).thenReturn(new Object[]{table});
        doThrow(new SecurityException("denied")).when(manage).requireDatasource(702L);
        assertThrows(SecurityException.class, () -> guard.check(point));verify(point,never()).proceed();
    }
    @Test public void forbiddenSqlNeverReachesQueryOrLogWriter() throws Throwable {
        var sql = new PreviewSqlDTO();sql.setDatasourceId(702L);
        when(point.getArgs()).thenReturn(new Object[]{sql});
        doThrow(new SecurityException("denied")).when(manage).requireDatasource(702L);
        assertThrows(SecurityException.class, () -> guard.check(point));verify(point,never()).proceed();
    }
    @Test public void selectedFieldMetadataReachesHandler() throws Throwable {
        var field = new DatasetTableFieldRequest();field.setDatasourceId(701L);
        when(point.getArgs()).thenReturn(new Object[]{field});when(point.proceed()).thenReturn("fields");
        assertEquals("fields",guard.check(point));verify(manage).requireDatasource(701L);
    }
    @Test public void schemaUsesSavedSelectedSourceConfigurationInsteadOfCallerCredentials() throws Throwable {
        var request = new BusiDsRequest();request.setId(701L);request.setType("forged");request.setConfiguration("forged");
        var stored = new CoreDatasource();stored.setType("mysql");stored.setConfiguration("saved config");
        when(sources.getCoreDatasource(701L)).thenReturn(stored);when(point.getArgs()).thenReturn(new Object[]{request});
        guard.check(point);verify(manage).requireDatasource(701L);verify(point).proceed();
        assertEquals("mysql",request.getType());
        assertEquals("saved config",new String(Base64.getDecoder().decode(request.getConfiguration()),StandardCharsets.UTF_8));
    }
    @Test public void forbiddenSchemaCannotReadConfigurationOrConnect() throws Throwable {
        var request = new BusiDsRequest();request.setId(702L);
        when(point.getArgs()).thenReturn(new Object[]{request});
        doThrow(new SecurityException("denied")).when(manage).requireDatasource(702L);
        assertThrows(SecurityException.class, () -> guard.check(point));verifyNoInteractions(sources);verify(point,never()).proceed();
    }
    @Test public void administratorAndNonLocalCallsRetainExistingBehavior() throws Throwable {
        LocalAccessContext.clear();guard.check(point);verifyNoInteractions(manage,sources);
        LocalAccessContext.set(new LocalAccessContext.Identity(1,"admin","Admin",true,null,null,null,"admin",Set.of()));
        guard.check(point);verifyNoInteractions(manage,sources);verify(point,times(2)).proceed();
    }
}
