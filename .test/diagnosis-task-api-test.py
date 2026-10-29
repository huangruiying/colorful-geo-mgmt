#!/usr/bin/env python3
"""本地诊断任务回归：真实 Java/MySQL 配合临时 HTTP 模型替身，不调用外部厂商。"""
import argparse
import concurrent.futures
import json
import re
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler

DATABASE = "colorful_geo"


def sql(statement, admin=False):
    """通过本地容器的已有环境读取凭据，不输出连接认证信息。"""
    connection = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' if admin or DATABASE != "colorful_geo" else 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -ugeo_app'
    command = ["docker", "compose", "-f", "docker/compose.yml", "exec", "-T", "mysql", "sh", "-c",
               connection + ' --default-character-set=utf8mb4 --batch --skip-column-names -D' + DATABASE]
    result = subprocess.run(command, input=statement, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("数据库验收语句失败：" + result.stderr.strip())
    return result.stdout.strip()


def cleanup(prefix):
    """按本轮随机标识清理前置档案与结果，不使用真实用户方案或渠道。"""
    if not re.fullmatch(r"qa-diagnosis-[a-f0-9]{12}", prefix):
        raise ValueError("清理标识无效")
    condition = "p.name LIKE '" + prefix + "%'"
    sql("START TRANSACTION;"
        "DELETE r FROM geo_diagnosis_task_result r JOIN geo_diagnosis_task t ON r.task_id=t.id JOIN geo_diagnosis_plan p ON t.plan_id=p.id WHERE " + condition + ";"
        "DELETE t FROM geo_diagnosis_task t JOIN geo_diagnosis_plan p ON t.plan_id=p.id WHERE " + condition + ";"
        "DELETE FROM geo_diagnosis_plan WHERE name LIKE '" + prefix + "%';"
        "DELETE FROM geo_llm_channel WHERE channel_code LIKE '" + prefix + "%';"
        "DELETE FROM geo_project_info WHERE name LIKE '" + prefix + "%';COMMIT;")
    remaining = sql("SELECT (SELECT COUNT(*) FROM geo_project_info WHERE name LIKE '" + prefix + "%')+"
                    "(SELECT COUNT(*) FROM geo_llm_channel WHERE channel_code LIKE '" + prefix + "%')+"
                    "(SELECT COUNT(*) FROM geo_diagnosis_plan WHERE name LIKE '" + prefix + "%');")
    assert remaining == "0", "清理后仍有前置档案"
    print("PASS: 本轮临时项目、方案、渠道及任务结果已精确清理。")


class ModelStub(BaseHTTPRequestHandler):
    """只接受本地人工测试问题，捕获实际协议请求以验证采样独立性。"""
    requests = []

    def log_message(self, *_):
        """避免请求日志输出正文和认证头。"""

    def do_POST(self):
        """返回确定的 Chat/Responses 响应或可控的限流错误。"""
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
                if size == 0:
                    while self.rfile.readline().strip():
                        pass
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            encoded_request = b"".join(chunks)
        else:
            encoded_request = self.rfile.read(int(self.headers["Content-Length"]))
        payload = json.loads(encoded_request)
        self.requests.append((self.path, payload))
        time.sleep(0.04)
        if self.path.startswith("/failure"):
            self.send_response(429)
            body = {"error": {"message": "dummy-private-remote-response"}}
        else:
            self.send_response(200)
            citation = {"type": "url_citation", "url": "https://example.invalid/source", "title": "本地引用样本"}
            if self.path.endswith("/responses"):
                text = payload["input"][0]["content"][0]["text"]
                body = {"model": "actual-responses-model", "status": "completed", "usage": {"total_tokens": 19},
                        "output": [{"type": "message", "content": [{"type": "output_text", "text": "本地回答：" + text, "annotations": [citation]}]}]}
            else:
                text = payload["messages"][0]["content"]
                body = {"model": "actual-chat-model", "usage": {"total_tokens": 17},
                        "choices": [{"message": {"content": "本地回答：" + text, "annotations": [citation]}, "finish_reason": "stop"}]}
            if self.path.startswith("/blank"):
                body["choices"][0]["message"]["content"] = " "
            body["api_key"] = "dummy-private-echo"
        encoded = json.dumps(body, ensure_ascii=False).encode()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def run(base_url, keep):
    """覆盖创建、去重、成功/失败、快照、分页、回滚与启动恢复前置数据。"""
    prefix = "qa-diagnosis-" + uuid.uuid4().hex[:12]
    opener = build_opener(ProxyHandler({}))
    server = ThreadingHTTPServer(("127.0.0.1", 0), ModelStub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    root = "http://127.0.0.1:" + str(server.server_port)
    ModelStub.requests = []
    passed = []
    keep_ready = False

    def api(path, body=None, method=None, expected=None):
        """调用真实服务，只回读 JSON 和状态码，不记录认证数据。"""
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        request = Request(base_url + path, data=data, method=method,
                          headers={"Content-Type": "application/json"})
        try:
            with opener.open(request, timeout=15) as response:
                status, value = response.status, json.loads(response.read())
        except HTTPError as error:
            status, value = error.code, json.loads(error.read())
        assert status == expected if expected is not None else 200 <= status < 300, (path, status, value.get("detail"))
        return value

    def model(name, strategy, route):
        """低优先级人工渠道只连接本地模型替身，不改变用户模型配置。"""
        body = {"channelCode": prefix + "-" + name, "channelName": prefix + " " + name,
                "providerCode": "local-qa", "strategyBeanName": strategy, "baseUrl": root + route,
                "modelName": "qa-model", "apiKey": "dummy-local-placeholder", "temperature": None,
                "maxTokens": 128, "score": -10000, "enabled": True}
        return api("/llm-channels", body), body

    def question(index):
        """人工中立题避免品牌限制，问题不包含项目背景。"""
        return {"text": "独立采样问题 " + str(index), "source": "MANUAL", "intent": "RECOMMENDATION",
                "brandScope": "NON_BRAND", "language": "中文"}

    def plan(name, channel_ids, count=2, repeat=2):
        """每个场景独立保存方案，避免修改用户已有记录。"""
        body = {"name": prefix + " " + name, "projectId": project["id"], "repeatCount": repeat,
                "channelIds": channel_ids, "questions": [question(index) for index in range(count)]}
        return api("/diagnosis-plans", body), body

    def create(plan_id, request_id=None, expected=None):
        """使用独立提交标识发起任务。"""
        return api("/diagnosis-tasks", {"planId": plan_id, "requestId": request_id or uuid.uuid4().hex}, expected=expected)

    def wait(task_id):
        """有界等待后台终态，不仅依赖创建接口或 HTTP 200。"""
        until = time.monotonic() + 25
        while time.monotonic() < until:
            detail = api("/diagnosis-tasks/" + str(task_id))
            if detail["status"] not in ("PENDING", "RUNNING"):
                return detail
            time.sleep(0.15)
        raise AssertionError("任务未在 25 秒内结束")

    try:
        project = api("/projects", {"name": prefix + " 项目", "websiteUrl": "https://example.invalid",
            "industry": "本地验收", "coreFeatures": "项目背景不能发送给被测模型", "targetAudience": "本地测试",
            "aliases": [], "competitors": [], "keywords": ["qa"]})
        chat, chat_body = model("chat", "chatCompletionsApiCommonStrategy", "/success-chat")
        responses, _ = model("responses", "responsesApiCommonStrategy", "/success-responses")
        failure, _ = model("failure", "chatCompletionsApiCommonStrategy", "/failure")
        blank, _ = model("blank", "chatCompletionsApiCommonStrategy", "/blank")
        browser, _ = model("browser", "chatCompletionsApiCommonStrategy", "/success-chat")
        success_plan, success_body = plan("双协议成功", [chat["id"], responses["id"]])
        submit = uuid.uuid4().hex
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            concurrent_tasks = list(pool.map(lambda _: create(success_plan["id"], submit), range(2)))
        task = concurrent_tasks[0]
        assert concurrent_tasks[1]["id"] == task["id"]
        success_body["name"] = prefix + " 已编辑方案"
        success_body["questions"][0]["text"] = "编辑后的问题不应改变旧任务"
        api("/diagnosis-plans/" + str(success_plan["id"]), success_body, "PUT")
        done = wait(task["id"])
        assert done["status"] == "SUCCEEDED" and done["progress"]["total"] == 8 and done["progress"]["succeeded"] == 8, (done["id"], done["status"], done["progress"])
        assert done["planName"].endswith("双协议成功")
        assert done["snapshot"]["questions"][0]["text"] == "独立采样问题 0"
        assert create(success_plan["id"], submit)["id"] == task["id"]
        passed.append("并发和重复提交只创建一个任务；方案编辑不改变历史快照；双协议 8 次全部成功")
        sample_page = api("/diagnosis-tasks/" + str(task["id"]) + "/results?size=3&page=0")
        assert len(sample_page["items"]) == 3 and sample_page["total"] == 8
        assert "answerText" not in sample_page["items"][0] and "rawResponse" not in sample_page["items"][0]
        sample = api("/diagnosis-tasks/" + str(task["id"]) + "/results/" + str(sample_page["items"][0]["id"]))
        assert sample["answerText"].startswith("本地回答") and len(sample["citations"]) == 1
        assert sample["modelName"].startswith("actual-") and sample["usageInfo"]["total_tokens"] in (17, 19)
        assert "dummy-private-echo" not in sample["rawResponse"] and "api_key" not in sample["rawResponse"]
        assert "dummy-local-placeholder" not in json.dumps(done)
        passed.append("分页摘要不携带正文；回答、引用、实际模型、用量、耗时和脱敏响应体真实落库回读")

        partial_plan, _ = plan("部分失败", [chat["id"], failure["id"]], 1, 2)
        partial = wait(create(partial_plan["id"])["id"])
        assert partial["status"] == "PARTIAL_FAILED" and partial["progress"]["succeeded"] == 2 and partial["progress"]["failed"] == 2
        failed_plan, _ = plan("全部失败", [failure["id"]], 1, 2)
        failed = wait(create(failed_plan["id"])["id"])
        assert failed["status"] == "FAILED" and failed["progress"]["failed"] == 2
        assert sum(path.startswith("/failure") for path, _ in ModelStub.requests) == 4
        passed.append("部分失败/全部失败终态与计数正确；限流失败继续其他采样且不自动重试")

        blank_plan, _ = plan("空回答", [blank["id"]], 1, 1)
        blank_task = wait(create(blank_plan["id"])["id"])
        assert blank_task["status"] == "FAILED" and blank_task["progress"]["succeeded"] == 0
        assert sum(path.startswith("/blank") for path, _ in ModelStub.requests) == 1
        passed.append("HTTP 200 但空白回答仍标记失败，不能伪装为成功采样，不自动重试")

        baseline = len(ModelStub.requests)
        drift = create(success_plan["id"])
        chat_body["maxTokens"] = 256
        api("/llm-channels/" + str(chat["id"]), chat_body, "PUT")
        drift_done = wait(drift["id"])
        assert drift_done["progress"]["failed"] >= 1
        assert len(ModelStub.requests) - baseline <= 4
        passed.append("渠道生成参数变化后拒绝未开始的该渠道采样，不静默使用新配置")

        browser_plan, _ = plan("浏览器验收", [browser["id"]], 12, 2)
        create(browser_plan["id"], submit, expected=400)
        api("/diagnosis-tasks/999999999", expected=404)
        api("/diagnosis-tasks/" + str(task["id"]) + "/results/999999999", expected=404)
        api("/diagnosis-tasks/" + str(partial["id"]) + "/results/" + str(sample["id"]), expected=404)
        api("/diagnosis-tasks?status=INVALID", expected=400)
        passed.append("提交标识跨方案复用被拒绝；缺失任务、缺失回答、跨任务读取和非法状态均正确返回错误")

        trigger = "qa_diag_" + uuid.uuid4().hex[:12]
        question_id = int(sql("SELECT id FROM geo_diagnosis_plan_question WHERE plan_id=" + str(browser_plan["id"]) + " ORDER BY id LIMIT 1;"))
        rollback_request = uuid.uuid4().hex
        sql("DELIMITER //\nCREATE TRIGGER " + trigger + " BEFORE INSERT ON geo_diagnosis_task_result FOR EACH ROW BEGIN IF NEW.question_id=" + str(question_id) + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA sample insert failure'; END IF; END//\nDELIMITER ;\n", admin=True)
        try:
            create(browser_plan["id"], rollback_request, expected=500)
            assert sql("SELECT COUNT(*) FROM geo_diagnosis_task WHERE request_id='" + rollback_request + "';") == "0"
        finally:
            sql("DROP TRIGGER IF EXISTS " + trigger + ";", admin=True)
        passed.append("真实 MySQL 触发器模拟采样插入失败，任务创建事务完整回滚，无半个任务")

        persistence_plan, _ = plan("结果保存失败", [chat["id"]], 2, 1)
        persistence_questions = sql("SELECT GROUP_CONCAT(id) FROM geo_diagnosis_plan_question WHERE plan_id=" + str(persistence_plan["id"]) + ";")
        save_trigger = "qa_diag_save_" + uuid.uuid4().hex[:12]
        sql("DELIMITER //\nCREATE TRIGGER " + save_trigger + " BEFORE UPDATE ON geo_diagnosis_task_result FOR EACH ROW BEGIN IF NEW.question_id IN (" + persistence_questions + ") AND NEW.status='SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA result update failure'; END IF; END//\nDELIMITER ;\n", admin=True)
        before_calls = len(ModelStub.requests)
        try:
            persistence = wait(create(persistence_plan["id"])["id"])
            assert persistence["status"] == "FAILED" and persistence["progress"]["failed"] == 2
            assert len(ModelStub.requests) - before_calls == 1
        finally:
            sql("DROP TRIGGER IF EXISTS " + save_trigger + ";", admin=True)
        passed.append("真实结果更新失败后停止后续采样，只调用模型一次；恢复数据库后任务正确终止，不重发回答")

        for path, body in ModelStub.requests:
            assert "项目背景不能发送" not in json.dumps(body, ensure_ascii=False)
            if path.endswith("/responses"):
                assert len(body["input"]) == 1 and body["input"][0]["role"] == "user" and body["store"] is False
                assert "max_output_tokens" in body and "temperature" not in body
            else:
                assert len(body["messages"]) == 1 and body["messages"][0]["role"] == "user"
                assert "max_tokens" in body and "temperature" not in body
        passed.append("实际 HTTP 请求只有独立 user 问题，无项目背景/历史回答，留空随机性不发送")

        if keep:
            keep_ready = True
            # 留一个不发网络请求的浏览器方案；其余终态任务便于页面查看。
            print("FIXTURE " + json.dumps({"prefix": prefix, "projectId": project["id"], "browserPlanId": browser_plan["id"],
                  "successTaskId": task["id"], "partialTaskId": partial["id"], "failedTaskId": failed["id"]}))
        for item in passed:
            print("PASS: " + item)
        print("SUMMARY: " + str(len(passed)) + " 个真实 API/MySQL 场景通过；模型为本地 HTTP 替身，未调用外部厂商。")
    except AssertionError:
        reasons = sql("SELECT r.status,COALESCE(r.error_message,''),COUNT(*) FROM geo_diagnosis_task_result r JOIN geo_diagnosis_task t ON r.task_id=t.id JOIN geo_diagnosis_plan p ON t.plan_id=p.id WHERE p.name LIKE '" + prefix + "%' GROUP BY r.status,r.error_message;")
        print("诊断失败分类：", reasons)
        print("本地替身收到请求数：", len(ModelStub.requests))
        raise
    finally:
        server.shutdown()
        server.server_close()
        if not keep or not keep_ready:
            cleanup(prefix)


def main():
    """支持完整回归或按已报告随机标识清理临时资源。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8016")
    parser.add_argument("--keep-fixtures", action="store_true")
    parser.add_argument("--cleanup-prefix")
    parser.add_argument("--database", default="colorful_geo")
    args = parser.parse_args()
    global DATABASE
    if args.database != "colorful_geo" and not re.fullmatch(r"colorful_geo_diagnosis_qa_[a-f0-9]{12}", args.database):
        raise ValueError("测试数据库名称无效")
    DATABASE = args.database
    if args.cleanup_prefix:
        cleanup(args.cleanup_prefix)
    else:
        run(args.base_url.rstrip("/"), args.keep_fixtures)


if __name__ == "__main__":
    main()
