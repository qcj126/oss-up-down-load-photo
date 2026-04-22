package diary.ossupdownloadphoto.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface FileAddService {
    Map<String, Object> addFileToDb(List<MultipartFile> files);
}
