package diary.ossupdownloadphoto.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PhotoMapper {
    Integer addPhotoToDb(long id, String photoType, String photoName, long photoSize, String photoFormat, long sortOrder, String photoStatus);

    Integer selectPhotoByTypeAndName(String photoType, String photoName);

    Integer updatePhotoStatusById(@Param("id") Long id, @Param("ossUrl") String ossUrl, @Param("photoStatus") String photoStatus);

    /**
     * 批量插入照片
     *
     * @param photoList 照片列表
     * @return 插入成功的记录数
     */
    Integer batchAddPhotoToDb(@Param("photoList") java.util.List<diary.ossupdownloadphoto.po.Photo> photoList);
}
