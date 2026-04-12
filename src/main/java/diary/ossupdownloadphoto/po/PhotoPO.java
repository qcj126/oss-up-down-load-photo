package diary.ossupdownloadphoto.po;

import lombok.Data;

@Data
public class PhotoPO {
        private Long id;
        private String photoType;
        private String photoName;
        private String ossUrl;
        private Long photoSize;
        private String photoFormat;
        private Long sortOrder;
        private String photoStatus;
        private String createTime;
        private String updateTime;
}
