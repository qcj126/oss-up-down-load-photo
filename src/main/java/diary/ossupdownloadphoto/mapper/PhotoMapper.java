package diary.ossupdownloadphoto.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PhotoMapper {
    Integer addPhotoToDb(long id, String photoType, String photoName, long photoSize, String photoFormat, long sortOrder, String photoStatus);

    Integer selectPhotoByTypeAndName(String photoType, String photoName);

    Integer updatePhotoStatusById(@Param("id") Long id, @Param("ossUrl") String ossUrl, @Param("photoStatus") String photoStatus);
}
