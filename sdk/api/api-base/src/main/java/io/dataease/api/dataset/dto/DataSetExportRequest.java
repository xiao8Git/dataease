package io.dataease.api.dataset.dto;

import lombok.Data;

/**
 * @Author Junjun
 */
@Data
public class DataSetExportRequest extends DatasetNodeDTO {
    private String filename;
    private String expressionTree;
    private boolean dataEaseBi;
    // Populated by the server from validated scope; snapshotted for async export/retry.
    private String scopeTenant;
    private String scopeUser;
}
