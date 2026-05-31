package com.scut.industrial_software.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class TaskFileListVO {

    private String taskId;

    private String taskStatus;

    private String directoryType;

    private List<TaskFileInfoVO> files;
}
