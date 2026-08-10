export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    // Перенаправляем запрос на официальный API Google Gemini
    url.hostname = 'generativelanguage.googleapis.com';
    
    // Клонируем заголовки и тело запроса
    const newRequest = new Request(url.toString(), {
      method: request.method,
      headers: request.headers,
      body: request.body,
      redirect: 'follow'
    });

    // Выполняем запрос с IP-адресов датацентров Cloudflare (США/Европа)
    const response = await fetch(newRequest);
    
    // Возвращаем ответ в приложение JARVIS
    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers
    });
  }
};
