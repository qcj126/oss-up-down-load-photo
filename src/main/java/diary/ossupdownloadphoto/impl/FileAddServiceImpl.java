package diary.ossupdownloadphoto.impl;

import diary.ossupdownloadphoto.config.consts.PhotoStatusConst;
import diary.ossupdownloadphoto.config.consts.PhotoTypeConst;
import diary.ossupdownloadphoto.mapper.PhotoMapper;
import diary.ossupdownloadphoto.service.FileAddService;
import diary.ossupdownloadphoto.service.RedisService;
import diary.ossupdownloadphoto.util.MyUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.util.HashMap;
import java.util.Map;

@Service
public class FileAddServiceImpl implements FileAddService {
    @Resource
    private PhotoMapper photoMapper;

    @Resource
    private RedisService redisService;

    @Override
    public Map<String, Object> addFileToDb(MultipartFile file) {
        try {
            if (ImageIO.read(file.getInputStream()) == null) {
                return Map.of("code", 500, "message", "文件不是图片类型", "data", null);
            }
            String photoFormat = file.getContentType();
            if (photoFormat == null || !photoFormat.startsWith("image")) {
                return Map.of("code", 500, "message", "文件不是图片类型", "data", null);
            }
            long id = MyUtils.getPrimaryKey();
            String photoType = PhotoTypeConst.PHOTO_TYPE_SWEETY;
            String photoName = file.getOriginalFilename();
            // 查看同一图片所属类别下是否有相同名称的图片
            Integer isExist = photoMapper.selectPhotoByTypeAndName(photoType, photoName);
            if (isExist > 0) {
                Map<String, String> map = new HashMap<>();
                map.put("photoType", photoType);
                map.put("photoName", photoName);
                return Map.of("code", 500, "message", "图片已存在", "data", map);
            }
            long photoSize = file.getSize();
            // 从redis中获取已上传成功图片的数量，然后数量+1作为这张图片的编号

            long sortOrder = redisService.getPhotoCount() + 1;
            String photoStatus = PhotoStatusConst.PHOTO_STATUS_PROCESSING;
            Integer count = photoMapper.addPhotoToDb(id, photoType, photoName, photoSize, photoFormat, sortOrder, photoStatus);
            if (count > 0) {
                // 更新redis中存储的图片数量
                redisService.updatePhotoCount(sortOrder);
                return Map.of("data", id);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Map.of("code", 500, "message", "图片入库失败", "data", null);
    }
}
