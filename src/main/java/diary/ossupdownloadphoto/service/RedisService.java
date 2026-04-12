package diary.ossupdownloadphoto.service;

public interface RedisService {
    Long getPhotoCount();

    void updatePhotoCount(long photoCount);
}
