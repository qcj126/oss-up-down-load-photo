package diary.ossupdownloadphoto.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface FileUploadService {
    void uploadAndSendMsgAsync(Map<String, Object> result, MultipartFile file);
}
