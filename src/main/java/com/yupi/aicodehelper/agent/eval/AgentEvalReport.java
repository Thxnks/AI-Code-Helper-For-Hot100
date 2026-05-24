package com.yupi.aicodehelper.agent.eval;

import java.util.DoubleSummaryStatistics;
import java.util.List;

public record AgentEvalReport(
        List<AgentEvalResult> results,
        double averageComposite,
        double minComposite,
        double maxComposite,
        double answerCorrectnessAvg,
        double toolSelectionAccuracyAvg,
        double turnEfficiencyAvg,
        double robustnessAvg,
        double planAdherenceAvg,
        long passCount,
        long failCount,
        double passRate
) {
    public static AgentEvalReport from(List<AgentEvalResult> results) {
        if (results.isEmpty()) {
            return new AgentEvalReport(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        DoubleSummaryStatistics composite = results.stream()
                .mapToDouble(r -> r.score().composite()).summaryStatistics();
        double answerAvg = results.stream()
                .mapToDouble(r -> r.score().answerCorrectness()).average().orElse(0);
        double toolAvg = results.stream()
                .mapToDouble(r -> r.score().toolSelectionAccuracy()).average().orElse(0);
        double efficiencyAvg = results.stream()
                .mapToDouble(r -> r.score().turnEfficiency()).average().orElse(0);
        double robustnessAvg = results.stream()
                .mapToDouble(r -> r.score().robustness()).average().orElse(0);
        double planAvg = results.stream()
                .mapToDouble(r -> r.score().planAdherence()).average().orElse(0);
        long passCount = results.stream().filter(AgentEvalResult::passed).count();

        return new AgentEvalReport(
                List.copyOf(results),
                composite.getAverage(), composite.getMin(), composite.getMax(),
                answerAvg, toolAvg, efficiencyAvg, robustnessAvg, planAvg,
                passCount, results.size() - passCount,
                (double) passCount / results.size()
        );
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Agent Evaluation Report ===\n\n");
        for (var r : results()) {
            sb.append("  ").append(r.summary()).append("\n");
        }
        sb.append("\n--- Aggregate ---\n");
        sb.append(String.format("  Composite: avg=%.2f min=%.2f max=%.2f\n",
                averageComposite(), minComposite(), maxComposite()));
        sb.append(String.format("  Dimensions: answerCorrectness=%.2f toolSelection=%.2f turnEfficiency=%.2f robustness=%.2f planAdherence=%.2f\n",
                answerCorrectnessAvg(), toolSelectionAccuracyAvg(),
                turnEfficiencyAvg(), robustnessAvg(), planAdherenceAvg()));
        sb.append(String.format("  Pass: %d/%d (%.0f%%)\n",
                passCount(), results().size(), passRate() * 100));
        return sb.toString();
    }
}
