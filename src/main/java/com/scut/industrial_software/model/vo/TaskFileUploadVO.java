package com.scut.industrial_software.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskFileUploadVO {

    private String taskId;

    private String fileName;

    private Long fileSize;

    private String fileSizeDisplay;

    private String directoryType;

    private LocalDateTime uploadTime;
}
