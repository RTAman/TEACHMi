package com.teachmi.backend.learning.gemini;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.task.TaskExecutor;
import com.teachmi.backend.TeachmiApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual loopback HTTP and PostgreSQL. Only the provider HTTP boundary and first startup's queue are replaced. */
@Tag("offline-http")
class OfflineHttpJourneyTest {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    final OfflineHttpTransport gateway=new OfflineHttpTransport();
    final List<Map<String,Object>> checks=new ArrayList<>();
    final List<Map<String,Object>> observations=new ArrayList<>();
    ConfigurableApplicationContext app;
    String base,dbUrl,dbUser,dbPassword;
    final String password="Test-"+UUID.randomUUID();
    final Path evidence=Path.of(System.getenv().getOrDefault("TEACHMI_HTTP_EVIDENCE_DIR","build/offline-http-evidence"));
    record Reply(int status,JsonNode body,HttpHeaders headers) {}
    record Sent(String key,Map<String,Object> body,JsonNode response) {}
    record Material(String id,String concept) {}
    final class Member {
        final String email="qa-"+UUID.randomUUID()+"@example.test";
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String csrf;
    }

    void check(String name,boolean passed) {
        checks.add(Map.of("check",name,"passed",passed));
        assertThat(passed).as(name).isTrue(); // Never print response bodies, lecture text or authentication values.
    }
    void database() throws Exception {
        String root=Objects.requireNonNull(System.getenv("DB_URL"),"Explicit local test DB required");
        check("explicit isolated loopback PostgreSQL",root.matches("jdbc:postgresql://127\\.0\\.0\\.1:55[0-9]{3}/postgres")
                && "true".equals(System.getenv("TEACHMI_OFFLINE_HTTP_TEST")));
        dbUser=Objects.requireNonNull(System.getenv("DB_USER"));
        dbPassword=Objects.requireNonNull(System.getenv("DB_PASSWORD"));
        String name="teachmi_test_http_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(root,dbUser,dbPassword);var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+name);
        }
        dbUrl=root.substring(0,root.lastIndexOf('/')+1)+name;
    }
    void start(boolean ai,boolean holdAnalysis) {
        var application=new SpringApplication(TeachmiApplication.class);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> {
            if(ai) {
                var definition=new RootBeanDefinition(GeminiTransport.class);
                definition.setInstanceSupplier(() -> gateway);
                definition.setPrimary(true);
                ((BeanDefinitionRegistry)context).registerBeanDefinition("offlineGeminiTransport",definition);
            }
            if(holdAnalysis) context.addBeanFactoryPostProcessor(factory -> {
                ((BeanDefinitionRegistry)factory).removeBeanDefinition("materialAnalysisExecutor");
                factory.registerSingleton("materialAnalysisExecutor",(TaskExecutor)task -> { /* simulate a lost in-memory queue */ });
            });
        });
        app=application.run("--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url="+dbUrl,"--spring.datasource.username="+dbUser,
                "--spring.datasource.password="+dbPassword,"--spring.datasource.hikari.maximum-pool-size=5",
                "--spring.datasource.hikari.minimum-idle=0","--spring.config.import=optional:file:./absent-offline-http.env",
                "--teachmi.auth.cookie-secure=false","--teachmi.auth.cookie-same-site=Lax",
                "--teachmi.assessment.demo-enabled=false","--teachmi.gemini.enabled="+ai,
                "--teachmi.gemini.api-key=offline-test-key","--teachmi.gemini.model=offline-model",
                "--teachmi.gemini.max-output-tokens=6144","--teachmi.gemini.requests-per-minute=1000",
                "--teachmi.gemini.requests-per-day=1000","--teachmi.gemini.member-requests-per-day=500",
                "--logging.level.root=WARN","--logging.level.org.hibernate.orm.jdbc.bind=OFF");
        base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
        if(ai) check("provider boundary is offline replacement",app.getBean(GeminiTransport.class)==gateway
                && app.getBean(GeminiGenerationService.class).getClass()==GeminiGenerationService.class);
    }
    void stop() { if(app!=null) { app.close();app=null; } }
    Reply exchange(Member member,String method,String path,Object body,String key,boolean csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(45));
        builder.header("Accept","application/json");
        if(key!=null) builder.header("Idempotency-Key",key);
        if(csrf && member.csrf!=null) builder.header("X-CSRF-Token",member.csrf);
        var publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8);
        if(body!=null) builder.header("Content-Type","application/json");
        var response=member.client.send(builder.method(method,publisher).build(),HttpResponse.BodyHandlers.ofByteArray());
        JsonNode parsed;
        try { parsed=response.body().length==0?json.nullNode():json.readTree(response.body()); }
        catch(RuntimeException invalid) { throw new IllegalStateException("Non-JSON HTTP response: "+response.statusCode()); }
        return new Reply(response.statusCode(),parsed,response.headers());
    }
    JsonNode call(Member member,String method,String path,Object body,String key,int status) throws Exception {
        var reply=exchange(member,method,path,body,key,true);
        check(method+" "+path.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27,}","{id}")+" status "+status+
                " code "+reply.body().path("code").asText(""),reply.status()==status);
        return reply.body();
    }
    JsonNode get(Member member,String path) throws Exception { return call(member,"GET",path,null,null,200); }
    void login(Member member,boolean create) throws Exception {
        var credentials=Map.of("email",member.email,"password",password);
        if(create) call(member,"POST","/api/v1/auth/signup",credentials,null,201);
        var reply=exchange(member,"POST","/api/v1/auth/login",credentials,null,false);
        check("login and CSRF issued",reply.status()==200 && !reply.body().path("csrfToken").asText().isBlank());
        member.csrf=reply.body().path("csrfToken").asText();
        String cookie=String.join(";",reply.headers().allValues("set-cookie"));
        check("local HttpOnly SameSite cookie",cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"));
        get(member,"/api/v1/auth/me");
    }
    String text(String name) throws Exception {
        String folder=System.getenv("TEACHMI_HTTP_SOURCE_DIR");
        if(folder!=null && Files.isRegularFile(Path.of(folder,name))) return Files.readString(Path.of(folder,name),StandardCharsets.UTF_8).replace("\uFEFF","");
        return "Integration principle\nApply the stated rule to the supplied conditions. When the conditions change, use the same rule and explain the reason using the source evidence. ".repeat(3);
    }
    Material material(Member member,String text,String label) throws Exception {
        var body=call(member,"POST","/api/v1/materials",Map.of("title",label,"text",text,
                "concept",Map.of("name",label,"scope","선택한 원문의 원리와 적용 조건")),UUID.randomUUID().toString(),201);
        return new Material(body.path("materialId").asText(),body.path("concepts").get(0).path("conceptId").asText());
    }
    Reply upload(Member member,String key,String name,byte[] file) throws Exception {
        String boundary="Teachmi"+UUID.randomUUID().toString().replace("-","");
        var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(file);bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/materials")).timeout(Duration.ofSeconds(45))
                .header("X-CSRF-Token",member.csrf).header("Idempotency-Key",key)
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        var response=member.client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(),json.readTree(response.body()),response.headers());
    }
    byte[] file(String kind,String source) throws Exception {
        var out=new ByteArrayOutputStream();
        if(kind.equals("pdf")) {
            try(var document=new PDDocument()) {
                var page=new PDPage();document.addPage(page);
                var fontPath=Path.of(System.getenv().getOrDefault("TEACHMI_HTTP_FONT","C:/Windows/Fonts/malgun.ttf"));
                PDFont font;
                if(Files.isRegularFile(fontPath)) font=PDType0Font.load(document,fontPath.toFile());
                else {
                    if(!StandardCharsets.US_ASCII.newEncoder().canEncode(source))
                        throw new IllegalStateException("Set TEACHMI_HTTP_FONT to a font that supports the selected source");
                    font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                }
                try(var content=new PDPageContentStream(document,page)) {
                    content.beginText();content.setFont(font,10);content.setLeading(13);content.newLineAtOffset(40,750);
                    content.showText("Integration source");
                    for(String line:source.replace("\uFEFF","").replace("\t"," ").lines().toList())
                        for(int i=0;i<line.length();i+=45) { content.newLine();content.showText(line.substring(i,Math.min(line.length(),i+45))); }
                    content.endText();
                }
                document.save(out);
            }
        } else if(kind.equals("pptx")) {
            try(var show=new XMLSlideShow()) {
                var slide=show.createSlide();var title=slide.createTextBox();title.setText("통합 검증 원문");
                var body=slide.createTextBox();body.setText(source);show.write(out);
            }
        } else {
            try(var show=new HSLFSlideShow()) {
                var slide=show.createSlide();var title=new HSLFTextBox();title.setText("통합 검증 원문");slide.addShape(title);
                var body=new HSLFTextBox();body.setText(source);slide.addShape(body);show.write(out);
            }
        }
        return out.toByteArray();
    }
    String session(Member member,Material material) throws Exception {
        return call(member,"POST","/api/v1/sessions",Map.of("materialId",material.id(),"conceptId",material.concept()),
                UUID.randomUUID().toString(),201).path("sessionId").asText();
    }
    String path(String session) { return "/api/v1/sessions/"+session; }
    JsonNode state(Member member,String session) throws Exception { return get(member,path(session)); }
    void mode(Member member,String session,String mode) throws Exception {
        call(member,"PUT",path(session)+"/mode",Map.of("mode",mode,"expectedVersion",state(member,session).path("version").asLong()),
                UUID.randomUUID().toString(),200);
        if(mode.equals("first")) call(member,"POST",path(session)+"/chat/start",
                Map.of("expectedVersion",state(member,session).path("version").asLong()),UUID.randomUUID().toString(),200);
    }
    Sent turn(Member member,String session,String kind) throws Exception {
        var request=new LinkedHashMap<String,Object>();
        request.put("kind",kind);request.put("expectedVersion",state(member,session).path("version").asLong());
        if(kind.equals("answer")) request.put("text","원문의 근거와 조건을 연결하여 원리 및 적용 이유를 설명합니다.");
        String key=UUID.randomUUID().toString();
        return new Sent(key,request,call(member,"POST",path(session)+"/turns",request,key,200));
    }
    void finishChat(Member member,String session) throws Exception {
        int attempts=0;
        while(state(member,session).path("stage").asText().equals("chat") && attempts++<5) turn(member,session,"answer");
        check("conversation reaches existing problem stage",state(member,session).path("stage").asText().equals("problem"));
    }
    JsonNode submit(Member member,String session,String problem,String answer) throws Exception {
        String key=UUID.randomUUID().toString();
        var body=Map.of("answer",answer,"work","선택한 원문의 조건과 이유를 설명한 오프라인 답안입니다.");
        var result=call(member,"POST",path(session)+"/problems/"+problem+"/attempts",body,key,201);
        int calls=gateway.count();
        check("attempt idempotent receipt",result.equals(call(member,"POST",path(session)+"/problems/"+problem+"/attempts",body,key,201))
                && gateway.count()==calls);
        return result;
    }
    void reteach(Member member,String session,String problem,String attempt,boolean help) throws Exception {
        call(member,"POST",path(session)+"/problems/"+problem+"/reteach",
                Map.of("attemptId",attempt,"expectedVersion",state(member,session).path("version").asLong()),UUID.randomUUID().toString(),200);
        if(help) turn(member,session,"hint");
        finishChat(member,session);
        check("same problem returned",state(member,session).path("problemReturn").path("problemId").asText().equals(problem));
        get(member,path(session)+"/problems/"+problem);
    }
    JsonNode report(Member member,String session) throws Exception {
        var result=call(member,"POST",path(session)+"/report",null,UUID.randomUUID().toString(),200);
        check("report stored",result.equals(get(member,path(session)+"/report")));
        var confirmed=call(member,"POST",path(session)+"/report/confirm",null,UUID.randomUUID().toString(),200);
        check("learning complete",confirmed.path("status").asText().equals("confirmed") &&
                state(member,session).path("status").asText().equals("completed"));
        return confirmed;
    }
    List<Map<String,Object>> ledger() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();
        try(var connection=DriverManager.getConnection(dbUrl,dbUser,dbPassword);var statement=connection.createStatement();
                var result=statement.executeQuery("SELECT state,last_error_code,count(*) FROM teachmi.learning_ai_generation GROUP BY state,last_error_code ORDER BY state,last_error_code")) {
            while(result.next()) rows.add(Map.of("state",result.getString(1),"diagnostic",Objects.toString(result.getString(2),"none"),"count",result.getInt(3)));
        }
        return rows;
    }
    void verifySavedCitations() throws Exception {
        int verified=0;
        try(var connection=DriverManager.getConnection(dbUrl,dbUser,dbPassword);
                var statement=connection.createStatement();var result=statement.executeQuery(
                        "SELECT content_json FROM teachmi.learning_ai_generation WHERE state='succeeded'")) {
            while(result.next()) {
                var response=json.readTree(result.getString(1));
                for(var evidence:response.path("evidence")) {
                    try(var query=connection.prepareStatement("SELECT text FROM teachmi.source_chunk WHERE id=?::uuid")) {
                        query.setString(1,evidence.path("sourceChunkId").asText());
                        try(var source=query.executeQuery()) {
                            check("stored AI evidence is exact source text",source.next() && source.getString(1).contains(evidence.path("quote").asText()));
                            verified++;
                        }
                    }
                }
            }
        }
        check("generated evidence audited",verified>0);
    }
    void observe(String kind,Reply reply) {
        observations.add(Map.of("kind",kind,"status",reply.status(),"code",reply.body().path("code").asText(),
                "hasRecovery",reply.body().has("recovery")));
    }

    @Test void actualHttpJourneyWithStoredSourcesAndRestarts() throws Exception {
        try {
            database();
            var member=new Member();var other=new Member();
            start(false,true);
            check("database health UP",get(member,"/actuator/health").path("status").asText().equals("UP"));
            var preflight=member.client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/sessions"))
                    .header("Origin","https://frontend.example.invalid").header("Access-Control-Request-Method","POST")
                    .header("Access-Control-Request-Headers","Content-Type,X-CSRF-Token")
                    .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
            observations.add(Map.of("kind","cross-origin-preflight","status",preflight.statusCode(),
                    "allowOrigin",preflight.headers().firstValue("access-control-allow-origin").isPresent()));
            login(member,true);login(other,true);
            var accounting=material(member,text("sample-accounting.txt"),"회계 원문");
            var economics=material(member,text("sample-economics.txt"),"경제 원문");
            check("CSRF missing rejected",exchange(member,"POST","/api/v1/materials",Map.of("text","검증"),"no-csrf",false).status()==403);
            check("anonymous material denied",exchange(new Member(),"GET","/api/v1/materials/"+accounting.id(),null,null,false).status()==401);
            var uploaded=new ArrayList<String>();
            for(String kind:List.of("pdf","ppt","pptx")) {
                byte[] file=file(kind,text("sample-accounting.txt"));
                String key=UUID.randomUUID().toString();
                var reply=upload(member,key,"integration."+kind,file);
                check(kind+" accepted for analysis",reply.status()==202 && reply.body().path("status").asText().equals("analyzing"));
                String id=reply.body().path("materialId").asText();uploaded.add(id);
                check(kind+" duplicate upload same material",upload(member,key,"integration."+kind,file).body().path("materialId").asText().equals(id));
            }
            String disabledSession=session(other,material(other,text("sample-accounting.txt"),"연결 대기 검증"));
            var disabled=exchange(other,"PUT",path(disabledSession)+"/mode",Map.of("mode","first","expectedVersion",0),UUID.randomUUID().toString(),true);
            check("disabled provider is explicit pending",disabled.status()==503 && disabled.body().path("code").asText().equals("LEARNING_CONTENT_UNAVAILABLE"));
            observe("disabled-provider",disabled);
            stop();start(true,false);
            Material fileLearning=null;
            for(String id:uploaded) {
                JsonNode status=null;
                for(int i=0;i<100;i++) {
                    status=get(member,"/api/v1/materials/"+id);
                    if(!status.path("status").asText().equals("analyzing")) break;
                    Thread.sleep(100);
                }
                check("queued file analysis restored on restart",status.path("status").asText().equals("done") && status.path("concepts").size()>0);
                call(other,"GET","/api/v1/materials/"+id,null,null,404);
                if(fileLearning==null) fileLearning=new Material(id,status.path("concepts").get(0).path("conceptId").asText());
            }
            call(member,"PATCH","/api/v1/materials/"+fileLearning.id()+"/concepts/"+fileLearning.concept(),
                    Map.of("scope","원문에 근거한 조건과 적용 이유"),null,200);
            try(var document=new PDDocument();var blank=new ByteArrayOutputStream()) {
                document.addPage(new PDPage());document.save(blank);
                var uploadedBlank=upload(member,UUID.randomUUID().toString(),"blank.pdf",blank.toByteArray());
                check("blank PDF accepted for asynchronous failure",uploadedBlank.status()==202);
                String blankId=uploadedBlank.body().path("materialId").asText();
                JsonNode blankState=null;
                for(int i=0;i<100;i++) {
                    blankState=get(member,"/api/v1/materials/"+blankId);
                    if(!blankState.path("status").asText().equals("analyzing")) break;
                    Thread.sleep(100);
                }
                check("blank PDF classified no_text",blankState.path("errorCode").asText().equals("no_text"));
                check("failed file retry increments version",call(member,"POST","/api/v1/materials/"+blankId+"/retry",null,
                        UUID.randomUUID().toString(),202).path("analysisVersion").asInt()==2);
                for(int i=0;i<100;i++) {
                    if(!get(member,"/api/v1/materials/"+blankId).path("status").asText().equals("analyzing")) break;
                    Thread.sleep(100);
                }
            }
            check("unsupported upload rejected",upload(member,UUID.randomUUID().toString(),"notes.txt","file".getBytes(StandardCharsets.UTF_8)).status()==415);
            check("empty upload rejected",upload(member,UUID.randomUUID().toString(),"empty.pdf",new byte[0]).status()==422);
            String learning=session(member,fileLearning);
            mode(member,learning,"first");
            turn(member,learning,"hint");turn(member,learning,"example");turn(member,learning,"dont_know");
            gateway.fault.set("bad-quote");
            var firstBody=Map.of("kind","answer","text","원문 근거를 확인하며 원리와 조건을 설명합니다.","expectedVersion",state(member,learning).path("version").asLong());
            String firstKey=UUID.randomUUID().toString();
            var bad=exchange(member,"POST",path(learning)+"/turns",firstBody,firstKey,true);
            check("B exact quote failure classified",bad.status()==502 &&
                    bad.body().path("recovery").path("diagnosticCode").asText().equals("EVIDENCE_QUOTE_NOT_EXACT"));
            observe("B-intentional-invalid-quote",bad);
            var firstReply=call(member,"POST",path(learning)+"/turns",firstBody,firstKey,200);
            var saved=state(member,learning);int before=gateway.count();
            stop();start(true,false);
            check("session and input restored after server restart",saved.equals(state(member,learning)));
            check("turn receipt survives restart without provider request",firstReply.equals(call(member,"POST",path(learning)+"/turns",firstBody,firstKey,200))
                    && gateway.count()==before);
            finishChat(member,learning);
            gateway.fault.set("long-quote");
            var cBad=exchange(member,"POST",path(learning)+"/problems",null,null,true);
            check("C v4 overlong quote rejected",cBad.status()==502 && cBad.body().path("code").asText().equals("INVALID_GEMINI_RESPONSE"));
            check("C exact validation diagnostic reaches HTTP",cBad.body().path("recovery").path("diagnosticCode").asText().equals("EVIDENCE_QUOTE_TOO_LONG")
                    && cBad.body().path("recovery").path("retryAllowed").asBoolean());
            observe("C-intentional-41-character-quote",cBad);
            observations.add(Map.of("kind","C-validation-ledger-diagnostic","rows",ledger()));
            var problems=call(member,"POST",path(learning)+"/problems",null,null,200);
            check("two independent problems and no answer exposure",problems.path("total").asInt()==2 &&
                    !problems.toString().contains("expectedAnswer") && !problems.toString().contains("gradingCriteria"));
            before=gateway.count();
            check("problem generation replay no new request",problems.equals(call(member,"POST",path(learning)+"/problems",null,null,200))
                    && gateway.count()==before);
            String p1=problems.path("problems").get(0).path("problemId").asText();
            String p2=problems.path("problems").get(1).path("problemId").asText();
            var wrong=submit(member,learning,p1,"오답");
            reteach(member,learning,p1,wrong.path("attemptId").asText(),true);
            check("correct retry",submit(member,learning,p1,"정답").path("judgement").asText().equals("correct"));
            wrong=submit(member,learning,p2,"오답");
            reteach(member,learning,p2,wrong.path("attemptId").asText(),false);
            check("repeated wrong retry",submit(member,learning,p2,"오답").path("judgement").asText().equals("wrong"));
            var solution=call(member,"POST",path(learning)+"/problems/"+p2+"/solution",null,null,200);
            check("solution available after repeated wrong",solution.has("solution") && solution.path("problemId").asText().equals(p2));
            var accountingReport=report(member,learning);
            check("correct-after-reteach outcome",accountingReport.path("application").path("problems").get(0).path("outcome").asText().equals("CORRECT_AFTER_RETEACH"));
            check("solution and wrong retained",accountingReport.path("application").path("problems").get(1).path("solutionViewed").asBoolean()
                    && accountingReport.path("application").path("problems").get(1).path("outcome").asText().equals("WRONG"));
            check("original support and reteach separated",accountingReport.path("explanation").path("deliveredSupport").path("total").asInt()==3
                    && accountingReport.path("explanation").path("reteachCount").asInt()==2);
            var evidence=get(member,path(learning)+"/evidence");
            check("remediation help retained",evidence.path("reteachHistory").get(0).path("evidence").path("support").path("delivered").path("hint").asInt()==1);
            String review=session(member,economics);
            mode(member,review,"review");turn(member,review,"dont_know");finishChat(member,review);
            var secondProblems=call(member,"POST",path(review)+"/problems",null,null,200).path("problems");
            submit(member,review,secondProblems.get(0).path("problemId").asText(),"정답");
            submit(member,review,secondProblems.get(1).path("problemId").asText(),"보류");
            var economicsReport=report(member,review);
            check("first try correct and pending review counted",economicsReport.path("application").path("independentCorrect").asInt()==1
                    && economicsReport.path("application").path("pendingReview").asInt()==1);
            call(member,"POST","/api/v1/auth/logout",null,null,204);
            check("logout session invalidated",exchange(member,"GET","/api/v1/auth/me",null,null,false).status()==401);
            stop();start(true,false);login(member,false);
            var home=get(member,"/api/v1/home");
            check("home history restored",home.path("recentReports").size()==2 && home.path("inProgress").isNull());
            check("accounting report restored",accountingReport.equals(get(member,path(learning)+"/report")));
            check("economics report restored",economicsReport.equals(get(member,path(review)+"/report")));
            for(String route:List.of("/api/v1/materials/"+accounting.id(),path(learning),path(learning)+"/evidence",
                    path(learning)+"/problems/"+p1,path(learning)+"/report")) call(other,"GET",route,null,null,404);
            var limitedMember=new Member();login(limitedMember,true);
            String limitedSession=session(limitedMember,material(limitedMember,text("sample-accounting.txt"),"시도 한도 검증"));
            mode(limitedMember,limitedSession,"review");
            var limitedBody=Map.of("kind","example","expectedVersion",state(limitedMember,limitedSession).path("version").asLong());
            String limitedKey=UUID.randomUUID().toString();
            gateway.fault.set("long-quote");
            var firstLimit=exchange(limitedMember,"POST",path(limitedSession)+"/turns",limitedBody,limitedKey,true);
            check("first quote failure allows explicit retry",firstLimit.status()==502 && firstLimit.body().path("recovery").path("retryAllowed").asBoolean());
            gateway.fault.set("long-quote");
            var exhausted=exchange(limitedMember,"POST",path(limitedSession)+"/turns",limitedBody,limitedKey,true);
            check("second quote failure immediately reports exhaustion",exhausted.status()==502
                    && exhausted.body().path("recovery").path("action").asText().equals("limit_reached")
                    && !exhausted.body().path("recovery").path("retryAllowed").asBoolean());
            var limitedState=state(limitedMember,limitedSession);before=gateway.count();var limitedLedger=ledger();
            check("retry after exhaustion is blocked before provider",exchange(limitedMember,"POST",path(limitedSession)+"/turns",limitedBody,limitedKey,true).status()==429
                    && gateway.count()==before);
            stop();start(true,false);
            check("exhaustion survives restart without changing ledger",limitedState.equals(state(limitedMember,limitedSession))
                    && limitedState.path("recovery").path("action").asText().equals("limit_reached")
                    && limitedLedger.equals(ledger()) && gateway.count()==before);
            var cFault=new Member();login(cFault,true);
            var cMaterial=material(cFault,text("sample-economics.txt"),"C 오류 검증");
            String cs=session(cFault,cMaterial);mode(cFault,cs,"review");finishChat(cFault,cs);
            gateway.fault.set("timeout");
            var cTimeout=exchange(cFault,"POST",path(cs)+"/problems",null,null,true);
            check("C uncertain stops this flow",cTimeout.status()==503 && cTimeout.body().path("code").asText().equals("AI_GENERATION_UNCERTAIN"));
            check("C initial uncertain includes recovery",cTimeout.body().path("recovery").path("action").asText().equals("review_required")
                    && !cTimeout.body().path("recovery").path("retryAllowed").asBoolean());
            observe("C-intentional-timeout",cTimeout); // No further generation requests for this member/session.
            var cHeld=state(cFault,cs);
            observations.add(Map.of("kind","C-uncertain-session-restore","hasRecovery",cHeld.has("recovery"),
                    "actions",json.convertValue(cHeld.path("availableActions"),List.class)));
            check("C uncertain is visible on the shared session GET",cHeld.path("recovery").path("action").asText().equals("review_required")
                    && cHeld.path("availableActions").get(0).asText().equals("wait"));
            var modeFault=new Member();login(modeFault,true);
            String ms=session(modeFault,material(modeFault,text("sample-accounting.txt"),"모드 오류 검증"));
            gateway.fault.set("timeout");
            var modeTimeout=exchange(modeFault,"PUT",path(ms)+"/mode",Map.of("mode","first","expectedVersion",0),UUID.randomUUID().toString(),true);
            check("B mode timeout is initially held",modeTimeout.status()==503 && modeTimeout.body().path("recovery").path("action").asText().equals("review_required"));
            var modeHeld=state(modeFault,ms);
            observations.add(Map.of("kind","B-mode-uncertain-session-restore","hasRecovery",modeHeld.has("recovery"),
                    "actions",json.convertValue(modeHeld.path("availableActions"),List.class)));
            check("B mode uncertain is visible after reconnect",modeHeld.path("recovery").path("action").asText().equals("review_required"));
            var bFault=new Member();login(bFault,true);
            var bMaterial=material(bFault,text("sample-accounting.txt"),"B 오류 검증");
            String bs=session(bFault,bMaterial);mode(bFault,bs,"review");
            gateway.fault.set("timeout");
            var bTimeout=exchange(bFault,"POST",path(bs)+"/turns",Map.of("kind","answer","text","저장된 설명",
                    "expectedVersion",state(bFault,bs).path("version").asLong()),UUID.randomUUID().toString(),true);
            check("B uncertain provides static recovery",bTimeout.status()==503
                    && bTimeout.body().path("recovery").path("action").asText().equals("review_required")
                    && bTimeout.body().path("recovery").path("diagnosticCode").asText().equals("TRANSPORT_REQUEST_TIMEOUT")
                    && !bTimeout.body().path("recovery").path("retryAllowed").asBoolean());
            observe("B-intentional-timeout",bTimeout);
            var ledger=ledger();before=gateway.count();
            var held=state(bFault,bs);
            check("B uncertain restored without spinner instruction",held.path("recovery").path("action").asText().equals("review_required"));
            stop();start(true,false);
            check("uncertain survives server restart",held.equals(state(bFault,bs)) && ledger.equals(ledger()) && gateway.count()==before);
            check("all uncertain receipts retained",ledger.stream().filter(row -> row.get("state").equals("uncertain")).mapToInt(row -> (Integer)row.get("count")).sum()==3);
            check("C provider also uses v4",gateway.audit.stream().filter(row -> row.get("operation").toString().startsWith("assessment/"))
                    .allMatch(row -> row.get("promptVersion").equals("v4")));
            verifySavedCitations();
            var flyway=app.getBean(org.flywaydb.core.Flyway.class);
            check("Flyway valid with no pending migrations",flyway.validateWithResult().validationSuccessful && flyway.info().pending().length==0);
            observations.add(Map.of("kind","migration-audit","applied",flyway.info().applied().length));
        } finally {
            stop();
            Files.createDirectories(evidence);
            var result=new LinkedHashMap<String,Object>();
            result.put("provider","offline-transport-only");result.put("realGeminiCalls",0);
            result.put("checks",checks);result.put("observations",observations);
            result.put("mockCalls",gateway.calls);result.put("providerAudit",gateway.audit);
            if(dbUrl!=null) result.put("ledger",ledger());
            Files.writeString(evidence.resolve("summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
}
