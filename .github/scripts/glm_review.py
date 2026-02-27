import os
import requests
from openai import OpenAI

# Konfiguracja klienta dla Zhipu AI
client = OpenAI(
    api_key=os.getenv("ZHIPUAI_API_KEY"),
    base_url="https://open.bigmodel.cn/api/paas/v4/"
)

def get_pr_diff():
    repo = os.getenv("REPO")
    pr_num = os.getenv("PR_NUMBER")
    token = os.getenv("GITHUB_TOKEN")
    url = f"https://api.github.com/repos/{repo}/pulls/{pr_num}"
    headers = {"Authorization": f"token {token}", "Accept": "application/vnd.github.v3.diff"}
    return requests.get(url, headers=headers).text

def post_comment(comment):
    repo = os.getenv("REPO")
    pr_num = os.getenv("PR_NUMBER")
    token = os.getenv("GITHUB_TOKEN")
    url = f"https://api.github.com/repos/{repo}/issues/{pr_num}/comments"
    headers = {"Authorization": f"token {token}"}
    requests.post(url, headers=headers, json={"body": comment})

diff = get_pr_diff()

# Wywołanie modelu GLM-4
response = client.chat.completions.create(
    model="glm-4", # Tutaj możesz sprecyzować konkretną wersję np. glm-4-0520
    messages=[
        {"role": "system", "content": "Jesteś ekspertem programowania. Przeanalizuj diff kodu i wypunktuj potencjalne błędy, problemy z wydajnością lub sugestie czystego kodu w języku polskim."},
        {"role": "user", "content": f"Oto zmiany w Pull Requeście:\n\n{diff}"}
    ]
)

review_text = "### 🤖 AI Code Review (GLM-4)\n\n" + response.choices[0].message.content
post_comment(review_text)