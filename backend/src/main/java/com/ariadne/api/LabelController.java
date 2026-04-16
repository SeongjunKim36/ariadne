package com.ariadne.api;

import com.ariadne.api.dto.LabelResponse;
import com.ariadne.api.dto.UpdateLabelRequest;
import com.ariadne.semantic.TierLabeler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/labels")
public class LabelController {

    private final TierLabeler tierLabeler;

    public LabelController(TierLabeler tierLabeler) {
        this.tierLabeler = tierLabeler;
    }

    @PostMapping("/generate")
    public List<LabelResponse> generate() {
        return tierLabeler.generateLabels().stream()
                .map(LabelResponse::from)
                .toList();
    }

    @GetMapping
    public List<LabelResponse> current() {
        return tierLabeler.currentLabels().stream()
                .map(LabelResponse::from)
                .toList();
    }

    @PutMapping("/{arn:.+}")
    public LabelResponse update(
            @PathVariable String arn,
            @RequestBody UpdateLabelRequest request
    ) {
        return LabelResponse.from(tierLabeler.updateLabel(arn, request.tier()));
    }
}
