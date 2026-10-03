package co.yixiang.yshop.module.pay.credential;

import co.yixiang.yshop.module.pay.controller.admin.merchantdetails.MerchantDetailsController;
import co.yixiang.yshop.module.pay.controller.admin.merchantdetails.vo.*;
import co.yixiang.yshop.module.pay.dal.dataobject.merchantdetails.MerchantDetailsDO;
import co.yixiang.yshop.module.pay.dal.mysql.merchantdetails.MerchantDetailsMapper;
import co.yixiang.yshop.module.pay.service.merchantdetails.MerchantDetailsServiceImpl;
import co.yixiang.yshop.framework.common.pojo.PageResult;
import co.yixiang.yshop.framework.apilog.core.filter.ApiAccessLogFilter;
import co.yixiang.yshop.framework.apilog.core.service.ApiAccessLogFrameworkService;
import co.yixiang.yshop.framework.apilog.core.service.ApiErrorLogFrameworkService;
import co.yixiang.yshop.framework.web.config.WebProperties;
import co.yixiang.yshop.framework.web.core.filter.CacheRequestBodyWrapper;
import co.yixiang.yshop.framework.web.core.handler.GlobalExceptionHandler;
import co.yixiang.yshop.framework.web.core.util.WebFrameworkUtils;
import co.yixiang.yshop.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import co.yixiang.yshop.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC endpoints, actual mapping/service/crypto/audit/error/export; mapper is isolated. */
class MerchantDetailsControllerSecurityTest {
    private final String secret="synthetic-controller-secret";
    private final PaymentCredentialCryptoService crypto=new PaymentCredentialCryptoService(PaymentCredentialCryptoServiceTest.randomKey());
    MockMvc mvc;
    MerchantDetailsMapper mapper;
    ApiAccessLogFrameworkService access;
    ApiErrorLogFrameworkService errors;
    MerchantDetailsDO row;
    @BeforeEach void setup() {
        new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(new org.springframework.beans.factory.support.DefaultListableBeanFactory());
        mapper=mock(MerchantDetailsMapper.class); access=mock(ApiAccessLogFrameworkService.class); errors=mock(ApiErrorLogFrameworkService.class);
        var service=new MerchantDetailsServiceImpl(); ReflectionTestUtils.setField(service,"merchantDetailsMapper",mapper); ReflectionTestUtils.setField(service,"credentialCrypto",crypto);
        var controller=new MerchantDetailsController(); ReflectionTestUtils.setField(controller,"merchantDetailsService",service);
        row=MerchantDetailsDO.builder().detailsId("test_merchant").payType("wxPay").appid("synthetic-app").mchId("synthetic-merchant").signType("MD5").isTest(1)
                .keyPrivate(crypto.encrypt("test_merchant","keyPrivate",secret)).keyCertPwd(crypto.encrypt("test_merchant","keyCertPwd",secret)).keyCert(crypto.encrypt("test_merchant","keyCert",secret)).build();
        when(mapper.selectById("test_merchant")).thenReturn(row);
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(row));
        when(mapper.selectPage(any(MerchantDetailsPageReqVO.class))).thenReturn(new PageResult<>(List.of(row),1L));
        when(mapper.selectList(any(MerchantDetailsExportReqVO.class))).thenReturn(List.of(row));
        Filter cache=(request,response,chain)-> {WebFrameworkUtils.setLoginUserType((HttpServletRequest)request,2); chain.doFilter(new CacheRequestBodyWrapper((HttpServletRequest)request),response);};
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler("test",errors))
                .addFilters(cache,new ApiAccessLogFilter(new WebProperties(),"test",access)).build();
    }
    void safeJson(MvcResult result) throws Exception {
        String json=result.getResponse().getContentAsString();
        assertFalse(json.contains(secret)); assertFalse(json.contains("enc:v1:"));
        for(String field:new String[]{"keyPrivate","privateKey","keyCertPwd","keyCert","apiV3Key","apiKey","mchKey","payKey"}) assertFalse(json.contains("\""+field+"\":"),field);
        assertTrue(json.contains("synthetic-merchant")); assertTrue(json.contains("privateKeyConfigured")); assertTrue(json.contains("certificatePasswordConfigured")); assertTrue(json.contains("keyCertificateConfigured"));
    }
    void safeAudit() throws Exception {
        var captor=ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class); verify(access,atLeastOnce()).createApiAccessLog(captor.capture());
        for(var dto:captor.getAllValues()) {String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(dto); assertFalse(json.contains(secret)); assertFalse(json.contains("enc:v1:"));}
    }
    String input() {return "{\"detailsId\":\"test_merchant\",\"payType\":\"wxPay\",\"signType\":\"MD5\",\"isTest\":1,\"appid\":\"synthetic-app\",\"mchId\":\"synthetic-merchant\",\"keyPrivate\":\""+secret+"\",\"keyCertPwd\":\""+secret+"\",\"keyCert\":\""+secret+"\"}";}
    @Test void createEncryptsAtActualEntryAndDoesNotAuditSecrets() throws Exception {
        String response=mvc.perform(post("/admin-api/pay/merchant-details/create").contextPath("/admin-api").contentType(MediaType.APPLICATION_JSON).content(input())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(secret)); var captor=ArgumentCaptor.forClass(MerchantDetailsDO.class); verify(mapper).insert(captor.capture());
        var stored=captor.getValue(); assertEquals(secret,crypto.decrypt(stored.getDetailsId(),"keyPrivate",stored.getKeyPrivate())); assertTrue(stored.getKeyCert().startsWith("enc:v1:")); safeAudit();
    }
    @Test void updateEncryptsAtActualEntryAndDoesNotAuditSecrets() throws Exception {
        mvc.perform(put("/admin-api/pay/merchant-details/update").contextPath("/admin-api").contentType(MediaType.APPLICATION_JSON).content(input())).andExpect(status().isOk());
        var captor=ArgumentCaptor.forClass(MerchantDetailsDO.class); verify(mapper).updateById(captor.capture()); assertTrue(captor.getValue().getKeyPrivate().startsWith("enc:v1:")); safeAudit();
    }
    @Test void getReturnsStatusOnly() throws Exception {safeJson(mvc.perform(get("/admin-api/pay/merchant-details/get").contextPath("/admin-api").param("id","test_merchant")).andExpect(status().isOk()).andReturn());safeAudit();}
    @Test void listReturnsStatusOnly() throws Exception {safeJson(mvc.perform(get("/admin-api/pay/merchant-details/list").contextPath("/admin-api").param("ids","test_merchant")).andExpect(status().isOk()).andReturn());safeAudit();}
    @Test void pageReturnsStatusOnly() throws Exception {safeJson(mvc.perform(get("/admin-api/pay/merchant-details/page").contextPath("/admin-api").param("pageNo","1").param("pageSize","10")).andExpect(status().isOk()).andReturn());safeAudit();}
    @Test void exportWorkbookContainsNeitherColumnsNorCiphertext() throws Exception {
        byte[] excel=mvc.perform(get("/admin-api/pay/merchant-details/export-excel").contextPath("/admin-api")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(excel))) {
            StringBuilder cells=new StringBuilder(); for(var sheet:workbook) for(var line:sheet) for(var cell:line) cells.append(cell.toString()).append('|');
            String text=cells.toString(); assertFalse(text.contains(secret)); assertFalse(text.contains("enc:v1:")); assertFalse(text.contains("私钥或私钥证书")); assertFalse(text.contains("私钥证书或key证书的密码"));
            assertTrue(text.contains("synthetic-merchant")); assertTrue(text.contains("已配置"));
        }
        safeAudit();
    }
    @Test void realUpdateFailureCannotExposeRequestOrExceptionMaterial() throws Exception {
        doThrow(new IllegalStateException(secret,new IllegalArgumentException(secret))).when(mapper).updateById(any(MerchantDetailsDO.class));
        String json=mvc.perform(put("/admin-api/pay/merchant-details/update").contextPath("/admin-api").contentType(MediaType.APPLICATION_JSON).content(input())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(json.contains(secret)); assertFalse(json.contains("enc:v1:"));safeAudit();
        var captor=ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class); verify(errors).createApiErrorLog(captor.capture());
        var dto=captor.getValue(); for(String value:new String[]{dto.getRequestParams(),dto.getExceptionMessage(),dto.getExceptionRootCauseMessage(),dto.getExceptionStackTrace()}) {assertNotNull(value); assertFalse(value.contains(secret)); assertFalse(value.contains("enc:v1:"));}
    }
    @Test void dtoAndDoSerializationAndToStringExcludeMaterial() throws Exception {
        var write=new ObjectMapper().readValue(input(),MerchantDetailsCreateReqVO.class);
        for(Object value:new Object[]{write,row}) {assertFalse(value.toString().contains(secret));assertFalse(value.toString().contains("enc:v1:"));String json=new ObjectMapper().writeValueAsString(value);assertFalse(json.contains(secret));assertFalse(json.contains("enc:v1:"));}
    }
}
