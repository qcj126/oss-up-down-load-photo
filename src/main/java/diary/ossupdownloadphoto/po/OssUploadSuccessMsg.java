package diary.ossupdownloadphoto.po;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OssUploadSuccessMsg {
    private Long id;
    private String ossUrl;
    private String photoName;
    private Long timestamp;
}
