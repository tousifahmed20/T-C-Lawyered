/**
 * OpenRouter adapter. OpenRouter proxies many models behind one
 * OpenAI-compatible Chat Completions API, so this mirrors the OpenAI adapter
 * with two deliberate differences:
 *
 *  1. No `response_format: json_object`. Many of OpenRouter's free models don't
 *     support JSON mode and reject the request outright. Our prompts already
 *     demand strict JSON and `parseJsonLoose` tolerates fenced/wrapped output,
 *     so we lean on that instead of a hard requirement.
 *  2. Optional attribution headers (HTTP-Referer / X-Title) OpenRouter uses for
 *     its app leaderboard — harmless, no tracking of the user.
 *
 * Conforms to the shared interface:
 *   call({ systemPrompt, userPrompt, model, apiKey, signal }) -> { text, tokensUsed }
 */
import { PROVIDER_ENDPOINTS, OPENROUTER_REFERER, OPENROUTER_TITLE } from '../../utils/CONSTANTS.js';
import { providerError } from '../llm.js';

export async function call({ systemPrompt, userPrompt, model, apiKey, signal }) {
  const res = await fetch(PROVIDER_ENDPOINTS.openrouter, {
    method: 'POST',
    signal,
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${apiKey}`,
      'HTTP-Referer': OPENROUTER_REFERER,
      'X-Title': OPENROUTER_TITLE,
    },
    body: JSON.stringify({
      model,
      max_tokens: 2048,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt },
      ],
    }),
  });

  if (!res.ok) throw providerError(res.status, await safeBody(res));

  const data = await res.json();
  // OpenRouter can surface upstream provider errors in a 200 body.
  if (data.error) throw providerError(data.error.code || 502, data.error.message || 'OpenRouter error');

  const text = data.choices?.[0]?.message?.content ?? '';
  const tokensUsed = data.usage?.total_tokens || 0;
  return { text, tokensUsed };
}

async function safeBody(res) {
  try {
    return JSON.stringify(await res.json());
  } catch {
    return res.statusText;
  }
}
