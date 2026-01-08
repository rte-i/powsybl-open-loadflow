package com.powsybl.demo.api;

import com.powsybl.demo.model.NetworkMetadata;
import com.powsybl.demo.model.StudyRequest;
import com.powsybl.demo.model.StudyResponse;
import com.powsybl.demo.service.StudyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class StudyController {

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
    }

    @GetMapping("/network")
    public NetworkMetadata describeNetwork() {
        return studyService.describeNetwork();
    }

    @PostMapping("/studies")
    public StudyResponse runStudy(@RequestBody(required = false) StudyRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Study request body is required");
        }
        if (request.branchFaults().isEmpty() && request.busFaults().isEmpty() && !request.includeAllBuses()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Specify at least one branch or bus fault to run the study");
        }
        return studyService.runStudy(request);
    }
}
