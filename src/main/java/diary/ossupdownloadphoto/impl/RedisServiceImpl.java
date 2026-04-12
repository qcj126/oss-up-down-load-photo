package diary.ossupdownloadphoto.impl;

import diary.ossupdownloadphoto.config.consts.RedisKeyConst;
import diary.ossupdownloadphoto.service.RedisService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisServiceImpl implements RedisService {
    @Resource
    private RedisTemplate<String, Object> photoRedisTemplate;

    @Override
    public Long getPhotoCount() {
        Object oCount = photoRedisTemplate.opsForValue().get(RedisKeyConst.PHOTO_COUNT_KEY);
        if (oCount == null) {
            return 0L;
        }
        return Long.parseLong((String) oCount);
    }

    @Override
    public void updatePhotoCount(long photoCount) {
        photoRedisTemplate.opsForValue().set(RedisKeyConst.PHOTO_COUNT_KEY, photoCount);
    }
}
