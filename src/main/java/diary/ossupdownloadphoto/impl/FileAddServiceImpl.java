package diary.ossupdownloadphoto.impl;

import diary.ossupdownloadphoto.config.consts.PhotoStatusConst;
import diary.ossupdownloadphoto.config.consts.PhotoTypeConst;
import diary.ossupdownloadphoto.mapper.PhotoMapper;
import diary.ossupdownloadphoto.service.FileAddService;
import diary.ossupdownloadphoto.service.RedisService;
import diary.ossupdownloadphoto.util.MyUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FileAddServiceImpl implements FileAddService {
    @Resource
    private PhotoMapper photoMapper;

    @Resource
    private RedisService redisService;

    @Override
    public Map<String, Object> addFileToDb(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Map.of("code", 500, "message", "文件列表为空", "data", "null");
        }

        List<Long> photoIds = new java.util.ArrayList<>();
        List<String> failedFiles = new java.util.ArrayList<>();

        for (MultipartFile file : files) {
            try {
                if (file.isEmpty()) {
                    failedFiles.add(file.getOriginalFilename() + ": 文件为空");
                    continue;
                }

                // 验证是否为图片类型
                String photoFormat = file.getContentType();
                if (photoFormat == null || !photoFormat.startsWith("image")) {
                    try {
                        if (ImageIO.read(file.getInputStream()) == null) {
                            failedFiles.add(file.getOriginalFilename() + ": 文件不是图片类型");
                            continue;
                        }
                    } catch (Exception e) {
                        failedFiles.add(file.getOriginalFilename() + ": 文件读取失败");
                        continue;
                    }
                }

                long id = MyUtils.getPrimaryKey();
                String photoType = PhotoTypeConst.PHOTO_TYPE_SWEETY;
                String photoName = file.getOriginalFilename();

                // 查看同一图片所属类别下是否有相同名称的图片
                Integer isExist = photoMapper.selectPhotoByTypeAndName(photoType, photoName);
                if (isExist > 0) {
                    failedFiles.add(photoName + ": 图片已存在");
                    continue;
                }

                long photoSize = file.getSize();
                // 从redis中获取已上传成功图片的数量，然后数量+1作为这张图片的编号
                long sortOrder = redisService.getPhotoCount() + 1;
                String photoStatus = PhotoStatusConst.PHOTO_STATUS_PROCESSING;

                Integer count = photoMapper.addPhotoToDb(id, photoType, photoName, photoSize, photoFormat, sortOrder, photoStatus);
                if (count > 0) {
                    // 更新redis中存储的图片数量
                    redisService.updatePhotoCount(sortOrder);
                    photoIds.add(id);
                } else {
                    failedFiles.add(photoName + ": 数据库插入失败");
                }
            } catch (Exception e) {
                log.error("处理文件 {} 时发生异常", file.getOriginalFilename(), e);
                failedFiles.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }

        if (photoIds.isEmpty()) {
            return Map.of("code", 500, "message", "所有文件处理失败", "data", "null", "failedFiles", failedFiles);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("data", photoIds);
        if (!failedFiles.isEmpty()) {
            result.put("failedFiles", failedFiles);
            result.put("message", "部分文件处理成功");
        }
        return result;
    }
}
