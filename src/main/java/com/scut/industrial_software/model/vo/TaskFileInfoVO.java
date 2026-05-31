package com.scut.industrial_software.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskFileInfoVO {

    private String fileName;

    private Long fileSize;

    private String fileSizeDisplay;

    private LocalDateTime lastModified;
}
