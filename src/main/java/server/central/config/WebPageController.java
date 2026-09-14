package server.central.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** AFS·DTN 화면과 기존 북마크 URL의 HTTP 상태 및 문서를 그대로 유지한다. */
@Controller
public class WebPageController {
    @GetMapping("/")
    String root()
    {
        return "redirect:/lnis/afstest/sender";
    }

    @GetMapping({"/lnis/afstest/sender", "/lnis/test/sender"})
    String sender()
    {
        return "forward:/afs-sender.html";
    }

    @GetMapping({"/lnis/afstest/receiver", "/lnis/test/receiver"})
    String receiver()
    {
        return "forward:/afs-receiver.html";
    }

    @GetMapping("/lnis/dtntest/sender")
    String dtn()
    {
        return "forward:/dtn-sender.html";
    }

    /** 수신 PC도 중앙 서버의 동일한 시험 결과를 조회한다. */
    @GetMapping("/lnis/dtntest/receiver")
    String dtnReceiver()
    {
        return "forward:/dtn-receiver.html";
    }
}

