/**
 * Anthropic Messages API adapter. Conforms to the shared provider interface:
 *   call({ systemPrompt, userPrompt, model, apiKey, signal }) -> { text, tokensUsed }
 */
import { PROVIDER_ENDPOINTS, ANTHROPIC_VERSION } from '../../utils/CONSTANTS.js';
import { providerError } from '../llm.js';

export async function call({ systemPrompt, userPrompt, model, apiKey, signal }) {
  const res = await fetch(PROVIDER_ENDPOINTS.anthropic, {
    method: 'POST',
    signal,
    headers: {
      'content-type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': ANTHROPIC_VERSION,
      // Required to call the API directly from an extension/browser context.
      'anthropic-dangerous-direct-browser-access': 'true',
    },
    body: JSON.stringify({
      model,
      max_tokens: 2048,
      system: systemPrompt,
      messages: [{ role: 'user', content: userPrompt }],
    }),
  });

  if (!res.ok) throw providerError(res.status, await safeBody(res));

  const data = await res.json();
  const text = (data.content || [])
    .filter((block) => block.type === 'text')
    .map((block) => block.text)
    .join('');
  const tokensUsed = (data.usage?.input_tokens || 0) + (data.usage?.output_tokens || 0);
  return { text, tokensUsed };
}

async function safeBody(res) {
  try {
    return JSON.stringify(await res.json());
  } catch {
    return res.statusText;
  }
}
