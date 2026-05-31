package com.scut.industrial_software.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scut.industrial_software.common.api.ApiResult;
import com.scut.industrial_software.common.exception.ApiException;
import com.scut.industrial_software.common.api.ApiErrorCode;
import com.scut.industrial_software.model.constant.TaskStatusConstants;
import com.scut.industrial_software.model.dto.ClientTaskStatusUpdateDTO;
import com.scut.industrial_software.model.dto.PageRequestDTO;
import com.scut.industrial_software.model.dto.RemoteTaskStartDTO;
import com.scut.industrial_software.model.dto.TaskCreateDTO;
import com.scut.industrial_software.model.dto.UserDTO;
import com.scut.industrial_software.model.entity.ModProjects;
import com.scut.industrial_software.model.entity.ModTasks;
import com.scut.industrial_software.model.entity.ModUsers;
import com.scut.industrial_software.model.entity.UserOrganization;
import com.scut.industrial_software.mapper.ModTasksMapper;
import com.scut.industrial_software.model.vo.ModTasksVO;
import com.scut.industrial_software.model.vo.TaskFileInfoVO;
import com.scut.industrial_software.model.vo.TaskFileListVO;
import com.scut.industrial_software.model.vo.TaskFileUploadVO;
import com.scut.industrial_software.service.IModProjectsService;
import com.scut.industrial_software.service.IModTasksService;
import com.scut.industrial_software.service.IModUsersService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.scut.industrial_software.service.IMonitorService;
import com.scut.industrial_software.service.IPermissionService;
import com.scut.industrial_software.utils.TaskDirectoryManager;
import com.scut.industrial_software.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jhx1301
 * @since 2025-05-28
 */
@Service
public class ModTasksServiceImpl extends ServiceImpl<ModTasksMapper, ModTasks> implements IModTasksService {

    @Autowired
    private IModProjectsService modProjectsService;

    @Autowired
    private IModUsersService modUsersService;

    @Autowired
    private IMonitorService monitorService;

    @Autowired
    private IPermissionService permissionService;

    @Autowired
    private TaskDirectoryManager taskDirectoryManager;

    @Value("${monitor.local-server.id:1}")
    private Integer localServerId;

    @Value("${monitor.local-server.name:local-server}")
    private String localServerName;

    private static final List<String> STAGE_TYPES = Arrays.asList("前处理", "后处理", "求解器");
    private static final List<String> PREPROCESSING_TYPES = Arrays.asList("多体", "结构", "冲击");
    private static final List<String> POSTPROCESSING_TYPES = List.of("通用后处理", "多体");
    private static final List<String> SOLVER_TYPES = List.of("多体", "结构", "冲击", "流固弱耦合");
    private static final String STATUS_PENDING = "pending";
    private static final int DEFAULT_PRIORITY = 2;
    private static final int DEFAULT_CPU_CORE_NEED = 1;
    private static final int DEFAULT_MEMORY_NEED = 4;

    @Override
    public ApiResult<?> getSharedTasksPage(Integer projectId, PageRequestDTO requestDTO) {
        // 先检查项目是否存在且为共享项目
        ModProjects project = modProjectsService.getById(projectId);
        if (project == null) {
            throw new ApiException(ApiErrorCode.PROJECT_NOT_FOUND);
        }

        if (project.getProjectStatus() != 0) {
            return ApiResult.failed("不是共享项目");
        }

        Page<ModTasks> page = new Page<>(requestDTO.getPageNum(), requestDTO.getPageSize());
        IPage<ModTasks> pageResult = baseMapper.selectSharedTasksByPage(page, projectId, requestDTO.getKeyword());

        List<ModTasksVO> modTasksVO = pageResult.getRecords().stream().map(this::toTaskVOWithDisplayStatus).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("records", modTasksVO);
        result.put("total", pageResult.getTotal());
        result.put("size", pageResult.getSize());
        result.put("current", pageResult.getCurrent());

        return ApiResult.success(result);
    }

    @Override
    public ApiResult<?> getPrivateTasksPage(Integer projectId, PageRequestDTO requestDTO) {
        // 先检查项目是否存在且为私人项目
        ModProjects project = modProjectsService.getById(projectId);
        if (project == null) {
            throw new ApiException(ApiErrorCode.PROJECT_NOT_FOUND);
        }

        if (project.getProjectStatus() != 1) {
            return ApiResult.failed("不是私人项目");
        }

        Page<ModTasks> page = new Page<>(requestDTO.getPageNum(), requestDTO.getPageSize());
        IPage<ModTasks> pageResult = baseMapper.selectPrivateTasksByPage(page, projectId, requestDTO.getKeyword());

        List<ModTasksVO> modTasksVO = pageResult.getRecords().stream().map(this::toTaskVOWithDisplayStatus).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("records", modTasksVO);
        result.put("total", pageResult.getTotal());
        result.put("size", pageResult.getSize());
        result.put("current", pageResult.getCurrent());

        return ApiResult.success(result);
    }

    @Override
    public ApiResult<?> createSharedTask(Integer projectId, TaskCreateDTO createDTO) {
        // 先检查项目是否存在且为共享项目
        ModProjects project = modProjectsService.getById(projectId);
        if (project == null) {
            throw new ApiException(ApiErrorCode.PROJECT_NOT_FOUND);
        }

        if (project.getProjectStatus() != 0) {
            return ApiResult.failed("不是共享项目");
        }

        // 校验输入参数（包括用户是否存在）
        ApiResult<?> validateResult = validateTaskParams(createDTO);
        if (validateResult != null) {
            return validateResult;
        }

        // 通过用户名查询用户ID
        Integer creatorId = getUserIdByUsername(createDTO.getCreator());

        ModTasks task = new ModTasks();
        task.setTaskName(createDTO.getTaskName());
        task.setCreatorId(creatorId);
        task.setCreator(createDTO.getCreator());
        task.setCreationTime(LocalDateTime.now());
        task.setProjectId(projectId);
        task.setSimulationStage(createDTO.getSimulationStage());
        task.setType(createDTO.getType());
        task.setStatus(STATUS_PENDING);
        task.setPriority(null);
        task.setCpuCoreNeed(DEFAULT_CPU_CORE_NEED);
        task.setMemoryNeed(DEFAULT_MEMORY_NEED);
        task.setProgress(0);
        task.setComputeResource(createDTO.getComputeResource());

        boolean IsSave = this.save(task);

        if (!IsSave){
            throw new ApiException(ApiErrorCode.TASK_CREATION_FAILED);
        }

        Integer taskId = task.getTaskId();
        if (taskId == null) {
            throw new ApiException(ApiErrorCode.TASK_CREATION_FAILED);
        }
        try {
            taskDirectoryManager.createTaskDirectories(creatorId, taskId);
        } catch (Exception ex) {
            this.removeById(taskId);
            throw new ApiException(ApiErrorCode.TASK_CREATION_FAILED);
        }

        ModTasksVO modTasksVO = toTaskVOWithDisplayStatus(task);

        return ApiResult.success(modTasksVO, "任务创建成功");
    }

    @Override
    public ApiResult<?> createPrivateTask(Integer projectId, TaskCreateDTO createDTO) {
        // 先检查项目是否存在且为私人项目
        ModProjects project = modProjectsService.getById(projectId);
        if (project == null) {
            throw new ApiException(ApiErrorCode.PROJECT_NOT_FOUND);
        }

        if (project.getProjectStatus() != 1) {
            return ApiResult.failed("不是私人项目");
        }

        // 校验输入参数（包括用户是否存在）
        ApiResult<?> validateResult = validateTaskParams(createDTO);
        if (validateResult != null) {
            return validateResult;
        }

        // 通过用户名查询用户ID
        Integer creatorId = getUserIdByUsername(createDTO.getCreator());

        ModTasks task = new ModTasks();
        task.setTaskName(createDTO.getTaskName());
        task.setCreatorId(creatorId);
        task.setCreator(createDTO.getCreator());
        task.setCreationTime(LocalDateTime.now());
        task.setProjectId(projectId);
        task.setSimulationStage(createDTO.getSimulationStage());
        task.setType(createDTO.getType());
        task.setStatus(STATUS_PENDING);
        task.setPriority(null);
        task.setCpuCoreNeed(DEFAULT_CPU_CORE_NEED);
        task.setMemoryNeed(DEFAULT_MEMORY_NEED);
        task.setProgress(0);
        task.setComputeResource(createDTO.getComputeResource());

        boolean IsSave = this.save(task);

        if (!IsSave){
            throw new ApiException(ApiErrorCode.TASK_CREATION_FAILED);
        }

        Integer taskId = task.getTaskId();
        if (taskId == null) {
            throw new ApiException(ApiErrorCode.TASK_CREATION_FAILED);
        }
        try {
            taskDirectoryManager.createTaskDirectories(creatorId, taskId);
        } catch (Exception ex) {
            this.removeById(taskId);
            throw new ApiException(ApiErrorCode.TASK_CREATION_FAILED);
        }

        ModTasksVO modTasksVO = toTaskVOWithDisplayStatus(task);

        return ApiResult.success(modTasksVO, "任务创建成功");

    }

    @Override
    public ApiResult<?> deleteTask(String taskId) {
        Integer taskIdInt;
        try {
            taskIdInt = taskId != null && taskId.startsWith("task_") ? Integer.parseInt(taskId.substring(5)) : Integer.parseInt(taskId);
        } catch (Exception ex) {
            return ApiResult.failed("任务ID格式不正确");
        }

        ModTasks task = this.getById(taskIdInt);
        if (task == null) {
            throw new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND);
        }

        // 填充创建者用户名信息
        if (task.getCreatorId() != null) {
            ModUsers creator = modUsersService.getById(task.getCreatorId());
            if (creator != null) {
                task.setCreator(creator.getUsername());
            }
        }

        boolean result = this.removeById(taskIdInt);
        if (result) {
            return ApiResult.success(null, "删除成功");
        } else {
            throw new ApiException(ApiErrorCode.TASK_DELETION_FAILED);
        }
    }

    @Override
    public ApiResult<?> startRemoteTask(String taskId, RemoteTaskStartDTO startDTO) {
        Integer taskIdInt = parseTaskId(taskId);
        if (taskIdInt == null) {
            return ApiResult.failed("任务ID格式不正确");
        }
        if (startDTO == null || startDTO.getPriority() == null || startDTO.getPriority() < 1 || startDTO.getPriority() > 3) {
            return ApiResult.failed("priority 仅支持 1|2|3");
        }

        ModTasks task = this.getById(taskIdInt);
        if (task == null) {
            throw new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND);
        }

        int updated = baseMapper.markTaskRemotePending(taskIdInt, localServerId, localServerName, startDTO.getPriority());
        if (updated <= 0) {
            return ApiResult.failed("仅 pending 且未进入远程调度的任务允许远程启动");
        }

        monitorService.dispatchPendingTasks();
        return monitorService.getProgramStatus(taskId);
    }

    @Override
    public ApiResult<?> updateClientTaskStatus(String taskId, ClientTaskStatusUpdateDTO updateDTO) {
        Integer taskIdInt = parseTaskId(taskId);
        if (taskIdInt == null) {
            return ApiResult.failed("任务ID格式不正确");
        }
        if (updateDTO == null || !StringUtils.hasText(updateDTO.getStatus())) {
            return ApiResult.failed("目标状态不能为空");
        }

        String toStatus = normalizeStatus(updateDTO.getStatus());
        if (!isClientTargetStatus(toStatus)) {
            return ApiResult.failed("客户端仅允许上报 running、completed、failed、stopped 状态");
        }
        if (updateDTO.getProgress() != null && (updateDTO.getProgress() < 0 || updateDTO.getProgress() > 100)) {
            return ApiResult.failed("progress 仅支持 0-100");
        }

        ModTasks task = this.getById(taskIdInt);
        if (task == null) {
            throw new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND);
        }
        if (task.getServerId() != null) {
            return ApiResult.failed("该任务已进入远程服务器调度，不能通过客户端接口修改状态");
        }

        String fromStatus = normalizeStatus(task.getStatus());
        if (!isAllowedClientTransition(fromStatus, toStatus)) {
            return ApiResult.failed("不允许从 " + fromStatus + " 变更为 " + toStatus);
        }

        Integer progress = resolveClientProgress(toStatus, updateDTO.getProgress());
        String errorMsg = (TaskStatusConstants.FAILED.equals(toStatus) || TaskStatusConstants.STOPPED.equals(toStatus))
                ? updateDTO.getErrorMsg()
                : null;

        int updated = baseMapper.updateLocalTaskStatusConditionally(taskIdInt, fromStatus, toStatus, progress, errorMsg);
        if (updated <= 0) {
            return ApiResult.failed("任务状态已变化，请刷新后重试");
        }
        return getTaskStatus(taskId);
    }

    @Override
    public ApiResult<?> getTaskStatus(String taskId) {
        return monitorService.getProgramStatus(taskId);
    }

    @Override
    public ApiResult<?> stopTask(String taskId) {
        return monitorService.stopProgram(taskId);
    }

    @Override
    public ApiResult<?> uploadTaskInput(String taskId, MultipartFile file, Boolean overwrite) {
        ModTasks task = getTaskOrThrow(taskId);
        validateTaskFileManagePermission(task);
        if (file == null || file.isEmpty()) {
            return ApiResult.failed("上传文件不能为空");
        }

        String originalFileName = file.getOriginalFilename();
        String fileName = sanitizeFileName(originalFileName);
        if (!StringUtils.hasText(fileName)) {
            return ApiResult.failed("文件名非法");
        }
        if (!isTaskEditable(task)) {
            return ApiResult.failed("当前任务状态不允许上传输入文件");
        }

        try {
            Path inputDir = taskDirectoryManager.getInputDir(task.getCreatorId(), task.getTaskId());
            Path targetFile = taskDirectoryManager.resolveFile(inputDir, fileName);
            if (Files.exists(targetFile) && !Boolean.TRUE.equals(overwrite)) {
                return ApiResult.failed("文件已存在");
            }

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            long fileSize = Files.size(targetFile);
            TaskFileUploadVO uploadVO = new TaskFileUploadVO();
            uploadVO.setTaskId(formatTaskId(task.getTaskId()));
            uploadVO.setFileName(fileName);
            uploadVO.setFileSize(fileSize);
            uploadVO.setFileSizeDisplay(taskDirectoryManager.formatFileSize(fileSize));
            uploadVO.setDirectoryType("input");
            uploadVO.setUploadTime(LocalDateTime.now());
            return ApiResult.success(uploadVO, "上传成功");
        } catch (IllegalArgumentException ex) {
            return ApiResult.failed("文件名非法");
        } catch (IOException ex) {
            throw new ApiException(ApiErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public ApiResult<?> listTaskInputFiles(String taskId) {
        ModTasks task = getTaskOrThrow(taskId);
        validateTaskFileReadPermission(task);

        try {
            Path inputDir = taskDirectoryManager.getInputDir(task.getCreatorId(), task.getTaskId());
            return ApiResult.success(buildTaskFileListVO(task, "input", taskDirectoryManager.listFiles(inputDir)));
        } catch (IOException ex) {
            throw new ApiException("查询任务输入文件失败");
        }
    }

    @Override
    public ApiResult<?> deleteTaskInputFile(String taskId, String fileName) {
        ModTasks task = getTaskOrThrow(taskId);
        validateTaskFileManagePermission(task);
        if (!isTaskEditable(task)) {
            return ApiResult.failed("当前任务状态不允许删除输入文件");
        }

        try {
            Path inputDir = taskDirectoryManager.getInputDir(task.getCreatorId(), task.getTaskId());
            Path targetFile = taskDirectoryManager.resolveFile(inputDir, fileName);
            if (!Files.isRegularFile(targetFile)) {
                return ApiResult.resourceNotFound("文件不存在");
            }
            Files.delete(targetFile);
            return ApiResult.success(null, "删除成功");
        } catch (IllegalArgumentException ex) {
            return ApiResult.failed("文件名非法");
        } catch (IOException ex) {
            throw new ApiException("删除任务输入文件失败");
        }
    }

    @Override
    public ApiResult<?> listTaskResultFiles(String taskId) {
        ModTasks task = getTaskOrThrow(taskId);
        validateTaskFileReadPermission(task);

        try {
            Path resultDir = resolveResultDirectory(task);
            return ApiResult.success(buildTaskFileListVO(task, "results", taskDirectoryManager.listFiles(resultDir)));
        } catch (IOException ex) {
            throw new ApiException("查询任务结果文件失败");
        }
    }

    @Override
    public ResponseEntity<byte[]> downloadTaskResultFile(String taskId, String fileName) {
        ModTasks task = getTaskOrThrow(taskId);
        validateTaskFileReadPermission(task);

        try {
            Path targetFile = resolveResultFile(task, fileName);
            if (!Files.isRegularFile(targetFile)) {
                return ResponseEntity.notFound().build();
            }

            String encodedFileName = encodeFileName(targetFile.getFileName().toString());
            String asciiFileName = new String(
                    targetFile.getFileName().toString().getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.ISO_8859_1
            );
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(targetFile))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + asciiFileName + "\"; filename*=UTF-8''" + encodedFileName)
                    .body(Files.readAllBytes(targetFile));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        } catch (IOException ex) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private ModTasks getTaskOrThrow(String taskId) {
        Integer taskIdInt = parseTaskId(taskId);
        if (taskIdInt == null) {
            throw new ApiException("任务ID格式不正确");
        }

        ModTasks task = this.getById(taskIdInt);
        if (task == null) {
            throw new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND);
        }
        return task;
    }

    private void validateTaskFileReadPermission(ModTasks task) {
        UserDTO currentUser = requireCurrentUser();
        ModProjects project = getTaskProjectOrThrow(task);
        if (permissionService.isCurrentUserSystemAdmin()) {
            return;
        }
        if (Objects.equals(currentUser.getId(), task.getCreatorId())
                || Objects.equals(currentUser.getId(), project.getCreator())) {
            return;
        }

        if (Integer.valueOf(1).equals(project.getProjectStatus())) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }

        if (Integer.valueOf(0).equals(project.getProjectStatus())) {
            UserOrganization userOrganization = modUsersService.getUserOrganizationRelation(currentUser.getId());
            Integer currentOrgId = userOrganization == null ? null : userOrganization.getOrgId();
            if (project.getOrganizationId() != null && project.getOrganizationId().equals(currentOrgId)) {
                return;
            }
        }

        throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private void validateTaskFileManagePermission(ModTasks task) {
        UserDTO currentUser = requireCurrentUser();
        ModProjects project = getTaskProjectOrThrow(task);
        if (permissionService.isCurrentUserSystemAdmin()) {
            return;
        }
        if (Objects.equals(currentUser.getId(), task.getCreatorId())
                || Objects.equals(currentUser.getId(), project.getCreator())) {
            return;
        }
        if (Integer.valueOf(0).equals(project.getProjectStatus())
                && permissionService.canManageOrganization(project.getOrganizationId())) {
            return;
        }
        throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private UserDTO requireCurrentUser() {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED);
        }
        return currentUser;
    }

    private ModProjects getTaskProjectOrThrow(ModTasks task) {
        if (task.getProjectId() == null) {
            throw new ApiException(ApiErrorCode.PROJECT_NOT_FOUND);
        }
        ModProjects project = modProjectsService.getById(task.getProjectId());
        if (project == null) {
            throw new ApiException(ApiErrorCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private boolean isTaskEditable(ModTasks task) {
        String status = normalizeStatus(task.getStatus());
        if (TaskStatusConstants.PENDING.equals(status)) {
            return task.getServerId() == null;
        }
        return TaskStatusConstants.STOPPED.equals(status)
                || TaskStatusConstants.FAILED.equals(status);
    }

    private TaskFileListVO buildTaskFileListVO(ModTasks task, String directoryType, List<TaskFileInfoVO> files) {
        TaskFileListVO listVO = new TaskFileListVO();
        listVO.setTaskId(formatTaskId(task.getTaskId()));
        listVO.setTaskStatus(normalizeStatus(task.getStatus()));
        listVO.setDirectoryType(directoryType);
        listVO.setFiles(files);
        return listVO;
    }

    private Path resolveResultDirectory(ModTasks task) throws IOException {
        Path outputDir = taskDirectoryManager.getOutputDir(task.getCreatorId(), task.getTaskId());
        if (!taskDirectoryManager.listFiles(outputDir).isEmpty()) {
            return outputDir;
        }
        return taskDirectoryManager.getInputDir(task.getCreatorId(), task.getTaskId());
    }

    private Path resolveResultFile(ModTasks task, String fileName) throws IOException {
        Path outputDir = taskDirectoryManager.getOutputDir(task.getCreatorId(), task.getTaskId());
        Path outputFile = taskDirectoryManager.resolveFile(outputDir, fileName);
        if (Files.isRegularFile(outputFile)) {
            return outputFile;
        }

        Path inputDir = taskDirectoryManager.getInputDir(task.getCreatorId(), task.getTaskId());
        Path inputFile = taskDirectoryManager.resolveFile(inputDir, fileName);
        if (Files.isRegularFile(inputFile)) {
            return inputFile;
        }
        return outputFile;
    }

    private String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String cleanedFileName = StringUtils.cleanPath(fileName.trim()).replace("\\", "/");
        int slashIndex = cleanedFileName.lastIndexOf('/');
        if (slashIndex >= 0) {
            cleanedFileName = cleanedFileName.substring(slashIndex + 1);
        }
        if (!isSimpleFileName(cleanedFileName)) {
            return null;
        }
        return cleanedFileName;
    }

    private boolean isSimpleFileName(String fileName) {
        return StringUtils.hasText(fileName)
                && !fileName.contains("/")
                && !fileName.contains("\\")
                && !".".equals(fileName)
                && !"..".equals(fileName);
    }

    private String formatTaskId(Integer taskId) {
        return "task_" + taskId;
    }

    private String encodeFileName(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 构建任务返回对象并统一转换状态展示文案
     */
    private ModTasksVO toTaskVOWithDisplayStatus(ModTasks task) {
        ModTasksVO taskVO = new ModTasksVO(task);
        taskVO.setStatus(convertTaskStatus(taskVO.getStatus()));
        return taskVO;
    }

    /**
     * 任务状态转换：pending->未启动，running->仿真中，completed/failed->已结束
     */
    private String convertTaskStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        return switch (status.toLowerCase()) {
            case "pending" -> "未启动";
            case "running" -> "仿真中";
            case "completed", "failed" -> "已结束";
            default -> status;
        };
    }

    /**
     * 通过用户名查询用户ID
     *
     * @param username 用户名
     * @return 用户ID
     */
    private Integer getUserIdByUsername(String username) {
        LambdaQueryWrapper<ModUsers> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ModUsers::getUsername, username);
        ModUsers user = modUsersService.getOne(wrapper);
        return user != null ? user.getUserId() : null;
    }

    private Integer parseTaskId(String taskId) {
        try {
            return taskId != null && taskId.startsWith("task_") ? Integer.parseInt(taskId.substring(5)) : Integer.parseInt(taskId);
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        return switch (status.trim()) {
            case "未启动" -> TaskStatusConstants.PENDING;
            case "仿真中" -> TaskStatusConstants.RUNNING;
            case "已完成" -> TaskStatusConstants.COMPLETED;
            case "已失败" -> TaskStatusConstants.FAILED;
            case "已停止" -> TaskStatusConstants.STOPPED;
            default -> status.trim().toLowerCase();
        };
    }

    private boolean isClientTargetStatus(String status) {
        return TaskStatusConstants.RUNNING.equals(status)
                || TaskStatusConstants.COMPLETED.equals(status)
                || TaskStatusConstants.FAILED.equals(status)
                || TaskStatusConstants.STOPPED.equals(status);
    }

    private boolean isAllowedClientTransition(String fromStatus, String toStatus) {
        if (TaskStatusConstants.PENDING.equals(fromStatus)) {
            return TaskStatusConstants.RUNNING.equals(toStatus)
                    || TaskStatusConstants.FAILED.equals(toStatus)
                    || TaskStatusConstants.STOPPED.equals(toStatus);
        }
        if (TaskStatusConstants.RUNNING.equals(fromStatus)) {
            return TaskStatusConstants.COMPLETED.equals(toStatus)
                    || TaskStatusConstants.FAILED.equals(toStatus)
                    || TaskStatusConstants.STOPPED.equals(toStatus);
        }
        return false;
    }

    private Integer resolveClientProgress(String toStatus, Integer requestedProgress) {
        if (TaskStatusConstants.COMPLETED.equals(toStatus)) {
            return 100;
        }
        if (TaskStatusConstants.RUNNING.equals(toStatus)) {
            return requestedProgress == null ? 0 : requestedProgress;
        }
        return requestedProgress;
    }

    /**
     * 校验任务参数
     *
     * @param createDTO 任务创建参数
     * @return 校验结果，null表示校验通过
     */
    private ApiResult<?> validateTaskParams(TaskCreateDTO createDTO) {
        if (!StringUtils.hasText(createDTO.getTaskName())) {
            return ApiResult.failed("任务名称不能为空");
        }

        // 校验创建者用户名
        if (!StringUtils.hasText(createDTO.getCreator())) {
            return ApiResult.failed("创建者不能为空");
        }

        // 校验用户是否存在
        Integer creatorId = getUserIdByUsername(createDTO.getCreator());
        if (creatorId == null) {
            return ApiResult.failed("创建者用户不存在");
        }

        // 校验仿真阶段
        if (!StringUtils.hasText(createDTO.getSimulationStage()) ||
                !STAGE_TYPES.contains(createDTO.getSimulationStage())) {
            return ApiResult.failed("仿真阶段不正确，应为：前处理、后处理、求解器");
        }

        // 校验任务类型与仿真阶段匹配
        if (!StringUtils.hasText(createDTO.getType())) {
            return ApiResult.failed("任务类型不能为空");
        }

        if ("后处理".equals(createDTO.getSimulationStage())) {
            if (!POSTPROCESSING_TYPES.contains(createDTO.getType())) {
                return ApiResult.failed("后处理阶段任务类型只能为：通用后处理/多体");
            }
        }

        if ("前处理".equals(createDTO.getSimulationStage())) {
            if (!PREPROCESSING_TYPES.contains(createDTO.getType())) {
                return ApiResult.failed("前处理阶段任务类型只能为：多体、结构、冲击");
            }
        }

        if ("求解器".equals(createDTO.getSimulationStage())) {
            if (!SOLVER_TYPES.contains(createDTO.getType())) {
                return ApiResult.failed("求解器阶段任务类型只能为：多体、结构、冲击、流固弱耦合");
            }
        }

        return null;
    }
}
