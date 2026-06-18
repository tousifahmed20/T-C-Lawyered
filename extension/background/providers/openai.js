/**
 * OpenAI Chat Completions adapter. Conforms to the shared provider interface.
 * Uses JSON mode (response_format) to coerce structured output where supported.
 */
import { PROVIDER_ENDPOINTS } from '../../utils/CONSTANTS.js';
import { providerError } from '../llm.js';

export async function call({ systemPrompt, userPrompt, model, apiKey, signal }) {
  const res = await fetch(PROVIDER_ENDPOINTS.openai, {
    method: 'POST',
    signal,
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model,
      max_tokens: 2048,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt },
      ],
    }),
  });

  if (!res.ok) throw providerError(res.status, await safeBody(res));

  const data = await res.json();
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
