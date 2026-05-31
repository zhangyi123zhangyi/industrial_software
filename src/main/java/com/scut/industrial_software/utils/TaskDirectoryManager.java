package com.scut.industrial_software.utils;

import com.scut.industrial_software.model.vo.TaskFileInfoVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
public class TaskDirectoryManager {

    private final Path tasksRootPath;

    public TaskDirectoryManager(@Value("${files.tasks.path}") String tasksRootPath) {
        if (!StringUtils.hasText(tasksRootPath)) {
            throw new IllegalStateException("files.tasks.path is required");
        }
        this.tasksRootPath = Paths.get(tasksRootPath).toAbsolutePath().normalize();
    }

    public Path createTaskDirectories(Integer userId, Integer taskId) throws IOException {
        if (userId == null || taskId == null) {
            throw new IllegalArgumentException("userId and taskId are required");
        }
        Path taskRoot = getTaskRoot(userId, taskId);
        Files.createDirectories(taskRoot.resolve("input"));
        Files.createDirectories(taskRoot.resolve("output"));
        Files.createDirectories(taskRoot.resolve("logs"));
        Files.createDirectories(taskRoot.resolve("scripts"));
        return taskRoot;
    }

    public Path getTaskRoot(Integer userId, Integer taskId) {
        if (userId == null || taskId == null) {
            throw new IllegalArgumentException("userId and taskId are required");
        }
        return tasksRootPath.resolve("user_" + userId).resolve("task_" + taskId).normalize();
    }

    public Path getInputDir(Integer userId, Integer taskId) throws IOException {
        Path inputDir = getTaskRoot(userId, taskId).resolve("input").normalize();
        Files.createDirectories(inputDir);
        return inputDir;
    }

    public Path getOutputDir(Integer userId, Integer taskId) throws IOException {
        Path outputDir = getTaskRoot(userId, taskId).resolve("output").normalize();
        Files.createDirectories(outputDir);
        return outputDir;
    }

    public Path resolveFile(Path directory, String fileName) {
        if (directory == null) {
            throw new IllegalArgumentException("directory is required");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (fileName.contains("/") || fileName.contains("\\")
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("fileName is invalid");
        }

        Path baseDir = directory.toAbsolutePath().normalize();
        Path filePath = baseDir.resolve(fileName).normalize();
        if (!filePath.startsWith(baseDir)) {
            throw new IllegalArgumentException("fileName is invalid");
        }
        return filePath;
    }

    public List<TaskFileInfoVO> listFiles(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(this::toTaskFileInfoVO)
                    .sorted(Comparator.comparing(TaskFileInfoVO::getLastModified).reversed())
                    .toList();
        }
    }

    public String formatFileSize(long fileSize) {
        if (fileSize < 1024) {
            return fileSize + " B";
        }
        double kb = fileSize / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.1f GB", mb / 1024.0);
    }

    private TaskFileInfoVO toTaskFileInfoVO(Path path) {
        try {
            TaskFileInfoVO fileInfo = new TaskFileInfoVO();
            long fileSize = Files.size(path);
            fileInfo.setFileName(path.getFileName().toString());
            fileInfo.setFileSize(fileSize);
            fileInfo.setFileSizeDisplay(formatFileSize(fileSize));
            fileInfo.setLastModified(LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    ZoneId.systemDefault()
            ));
            return fileInfo;
        } catch (IOException ex) {
            throw new IllegalStateException("读取任务文件信息失败", ex);
        }
    }
}

