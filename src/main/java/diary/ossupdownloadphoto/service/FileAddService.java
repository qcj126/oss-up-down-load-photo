package diary.ossupdownloadphoto.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface FileAddService {
    Map<String, Object> addFileToDb(MultipartFile file);
}
