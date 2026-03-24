package com.zwbd.agentnexus.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/11/13 14:59
 * @Desc:
 */
@Slf4j
@Service
public class FileStorageService {

    private final Path fileStorageLocation;

    /**
     * @param uploadDir application.properties 中配置的上传目录，例如: file.upload-dir=./uploads
     */
    public FileStorageService(@Value("${file.upload-dir}") String uploadDir) {
        this.fileStorageLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new RuntimeException("无法创建用于存储上传文件的目录。", ex);
        }
    }

    /**
     * 存储文件到本地
     *
     * @param file 上传的文件
     * @return 存储在磁盘上的唯一文件名 (例如 "uuid-abc.pdf")
     * @throws RuntimeException 如果存储失败
     */
    public String store(MultipartFile file) {
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        try {
            if (originalFileName.contains("..")) {
                throw new RuntimeException("文件名包含无效的路径序列 " + originalFileName);
            }

            String fileExtension = "";
            int i = originalFileName.lastIndexOf('.');
            if (i > 0) {
                fileExtension = originalFileName.substring(i);
            }
            // 使用UUID确保文件名唯一
            String newFileName = UUID.randomUUID().toString() + fileExtension;

            Path targetLocation = this.fileStorageLocation.resolve(newFileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            log.info("存储文件 {} 为 {}", originalFileName, newFileName);

            return newFileName; // 返回新文件名
        } catch (IOException ex) {
            log.error("无法存储文件 {}. 请重试!", originalFileName, ex);
            throw new RuntimeException("无法存储文件 " + originalFileName + ". 请重试!", ex);
        }
    }

    /**
     * 加载文件作为资源（用于下载或前端预览）
     *
     * @param storedFilename 存储在磁盘上的文件名
     * @return Resource 对象
     */
    public Resource loadFileAsResource(String storedFilename) {
        try {
            Path filePath = this.fileStorageLocation.resolve(storedFilename).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            } else {
                throw new RuntimeException("File not found " + storedFilename);
            }
        } catch (MalformedURLException ex) {
            throw new RuntimeException("File not found " + storedFilename, ex);
        }
    }

    /**
     * 从本地磁盘删除文件
     *
     * @param storedFilename 存储在磁盘上的唯一文件名
     * @throws RuntimeException 如果删除失败
     */
    public void delete(String storedFilename) {
        try {
            Path filePath = this.fileStorageLocation.resolve(storedFilename).normalize();
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("文件 {} 已从本地删除", storedFilename);
            } else {
                log.warn("尝试删除一个不存在的文件: {}", storedFilename);
            }
        } catch (IOException ex) {
            log.error("无法删除文件 {}.", storedFilename, ex);
            throw new RuntimeException("无法删除文件 " + storedFilename, ex);
        }
    }

    /**
     * 获取文件的完整存储路径
     * @param storedFilename 存储在磁盘上的唯一文件名
     * @return 文件的完整路径
     */
    public Path getPath(String storedFilename) {
        return this.fileStorageLocation.resolve(storedFilename).normalize();
    }
}
