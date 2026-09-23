package server.central.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** DTN 화면을 제공하고 기존 AFS 북마크는 DTN으로 이동한다. */
@Controller
public class WebPageController {
    @GetMapping("/lnis/data-management")
    String dataManagement() { return "forward:/data-management.html"; }

    @GetMapping("/dtn-intro")
    String dtnIntro()
    {
        return "forward:/dtn-intro.html";
    }

    @GetMapping("/")
    String root()
    {
        return "redirect:/lnis/dtntest/sender";
    }

    @GetMapping({"/lnis/afstest/sender", "/lnis/test/sender", "/afs-sender.html"})
    String sender()
    {
        return "redirect:/lnis/dtntest/sender";
    }

    @GetMapping({"/lnis/afstest/receiver", "/lnis/test/receiver", "/afs-receiver.html"})
    String receiver()
    {
        return "redirect:/lnis/dtntest/receiver";
    }

    @GetMapping({"/lnis/dtntest/sender", "/lnis/dtntest/sender/clear"})
    String dtn()
    {
        return "forward:/dtn-sender.html";
    }

    /** 수신 PC도 중앙 서버의 동일한 시험 결과를 조회한다. */
    @GetMapping({"/lnis/dtntest/receiver", "/lnis/dtntest/receiver/clear"})
    String dtnReceiver()
    {
        return "forward:/dtn-receiver.html";
    }
}

