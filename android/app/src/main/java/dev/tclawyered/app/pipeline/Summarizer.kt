package dev.tclawyered.app.pipeline

import dev.tclawyered.app.core.Chunker
import dev.tclawyered.app.core.Prompts
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.llm.LlmConfig
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.model.Summary

/**
 * Chunked summarization pipeline (F-06) — port of chunker.js summarizeDocument.
 * Short docs: one call. Long docs: overlapping section summaries + a meta pass.
 */
class Summarizer(private val llm: LlmClient) {

    suspend fun summarize(text: String, domain: String, type: PolicyType, config: LlmConfig): Summary {
        if (!Chunker.needsChunking(text)) {
            val result = llm.call(config, Prompts.singleSummary(text, domain, type))
            return SummaryMapper.shapeSummary(SummaryMapper.parse(result.text))
        }

        val chunks = Chunker.split(text)
        val sections = chunks.mapIndexed { i, chunk ->
            val result = llm.call(config, Prompts.sectionSummary(chunk, i, chunks.size))
            val (summary, points) = SummaryMapper.section(SummaryMapper.parse(result.text))
            Prompts.SectionResult(summary, points)
        }

        val meta = llm.call(config, Prompts.metaSummary(sections, domain, type))
        return SummaryMapper.shapeSummary(SummaryMapper.parse(meta.text))
    }
}
