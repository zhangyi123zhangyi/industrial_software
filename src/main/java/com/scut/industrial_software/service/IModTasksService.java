package com.scut.industrial_software.service;

import com.scut.industrial_software.common.api.ApiResult;
import com.scut.industrial_software.model.dto.ClientTaskStatusUpdateDTO;
import com.scut.industrial_software.model.dto.PageRequestDTO;
import com.scut.industrial_software.model.dto.RemoteTaskStartDTO;
import com.scut.industrial_software.model.dto.TaskCreateDTO;
import com.scut.industrial_software.model.entity.ModTasks;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author jhx1301
 * @since 2025-05-28
 */
public interface IModTasksService extends IService<ModTasks> {

    /**
     * 获取共享任务分页列表
     * 
     * @param projectId 项目ID
     * @param requestDTO 分页请求参数
     * @return 分页列表
     */
    ApiResult<?> getSharedTasksPage(Integer projectId, PageRequestDTO requestDTO);
    
    /**
     * 获取私人任务分页列表
     * 
     * @param projectId 项目ID
     * @param requestDTO 分页请求参数
     * @return 分页列表
     */
    ApiResult<?> getPrivateTasksPage(Integer projectId, PageRequestDTO requestDTO);
    
    /**
     * 创建新共享任务
     * 
     * @param projectId 项目ID
     * @param createDTO 创建参数
     * @return 创建结果
     */
    ApiResult<?> createSharedTask(Integer projectId, TaskCreateDTO createDTO);
    
    /**
     * 创建新私人任务
     * 
     * @param projectId 项目ID
     * @param createDTO 创建参数
     * @return 创建结果
     */
    ApiResult<?> createPrivateTask(Integer projectId, TaskCreateDTO createDTO);
    
    /**
     * 删除任务
     * 
     * @param taskId 任务ID
     * @return 操作结果
     */
    ApiResult<?> deleteTask(String taskId);

    ApiResult<?> startRemoteTask(String taskId, RemoteTaskStartDTO startDTO);

    ApiResult<?> updateClientTaskStatus(String taskId, ClientTaskStatusUpdateDTO updateDTO);

    ApiResult<?> getTaskStatus(String taskId);

    ApiResult<?> stopTask(String taskId);

    ApiResult<?> uploadTaskInput(String taskId, MultipartFile file, Boolean overwrite);

    ApiResult<?> listTaskInputFiles(String taskId);

    ApiResult<?> deleteTaskInputFile(String taskId, String fileName);

    ApiResult<?> listTaskResultFiles(String taskId);

    ResponseEntity<byte[]> downloadTaskResultFile(String taskId, String fileName);
}
