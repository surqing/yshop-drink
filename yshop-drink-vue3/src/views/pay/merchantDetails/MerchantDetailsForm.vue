<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible">
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="150px"
      v-loading="formLoading"
    >
      <el-form-item label="支付类型(支付渠道)" prop="payType">
        <el-select v-model="formData.payType">
          <el-option label="支付宝支付" value="aliPay" />
          <el-option label="微信支付" value="wxPay" />
        </el-select>
      </el-form-item>
      <el-form-item label="支付id" prop="detailsId">
        <el-select v-model="formData.detailsId" :disabled="formType === 'update'" filterable allow-create default-first-option>
          <!-- <el-option  :value="tenantId" /> -->
          <el-option label="微信支付小程序" :value="'wx_miniapp'" />
          <el-option label="微信支付公众号" :value="'wx_wechat'" />
          <el-option label="微信支付H5" :value="'wx_h5'" />
          <el-option label="支付宝H5" :value="'ali_h5'" />
        </el-select>
      </el-form-item>
      <!-- <el-form-item label="支付id" prop="detailsId">
        <el-input v-model="formData.detailsId" placeholder="请输入支付id" />
      </el-form-item> -->
      <el-form-item label="应用id" prop="appid">
        <el-input v-model="formData.appid" placeholder="请输入应用id" />
      </el-form-item>
      <el-form-item label="微信商户id" prop="mchId">
        <el-input v-model="formData.mchId" placeholder="请输入微信商户id" />
      </el-form-item>
      <el-form-item label="支付宝商户id" prop="seller">
        <el-input v-model="formData.seller" placeholder="请输入支付宝商户id" />
      </el-form-item>
      <el-form-item label="证书存储类型" prop="certStoreType">
        <el-select v-model="formData.certStoreType" placeholder="请选择类型" clearable>
          <el-option label="PATH" value="PATH" />
          <el-option label="STR" value="STR" />
          <el-option label="INPUT_STREAM" value="INPUT_STREAM" />
          <el-option label="CLASS_PATH" value="CLASS_PATH" />
          <el-option label="URL" value="URL" />
        </el-select>
        <div style="color: red;">注意：需要证书的选择不需要不选择</div>
      </el-form-item>
      <el-form-item label="私钥 / API 密钥" prop="keyPrivate">
        <div class="w-full">
          <template v-if="formType === 'update'">
            <el-tag :type="credentialStatus.privateKeyConfigured ? 'success' : 'info'">
              {{ credentialStatus.privateKeyConfigured ? '已配置' : '未配置' }}
            </el-tag>
            <el-button link type="primary" @click="replacement.keyPrivate = !replacement.keyPrivate">
              {{ replacement.keyPrivate ? '取消替换' : '替换私钥 / API 密钥' }}
            </el-button>
          </template>
          <el-input v-if="formType === 'create' || replacement.keyPrivate" v-model="formData.keyPrivate"
            type="password" autocomplete="new-password" placeholder="输入新凭据；留空保持服务器原值" />
        </div>
      </el-form-item>
      <el-form-item label="公钥或公钥证书" prop="keyPublic">
        <el-input v-model="formData.keyPublic" placeholder="请输入公钥或公钥证书" />
      </el-form-item>
      <el-form-item label="附加证书" prop="keyCert">
        <div class="w-full">
          <template v-if="formType === 'update'">
            <el-tag :type="credentialStatus.keyCertificateConfigured ? 'success' : 'info'">
              {{ credentialStatus.keyCertificateConfigured ? '已配置' : '未配置' }}
            </el-tag>
            <el-button link type="primary" @click="replacement.keyCert = !replacement.keyCert">
              {{ replacement.keyCert ? '取消替换' : '替换附加证书' }}
            </el-button>
          </template>
          <el-input v-if="formType === 'create' || replacement.keyCert" v-model="formData.keyCert"
            type="password" autocomplete="new-password" placeholder="输入新凭据；留空保持服务器原值" />
        </div>
      </el-form-item>
      <el-form-item label="证书密码" prop="keyCertPwd">
        <div class="w-full">
          <template v-if="formType === 'update'">
            <el-tag :type="credentialStatus.certificatePasswordConfigured ? 'success' : 'info'">
              {{ credentialStatus.certificatePasswordConfigured ? '已配置' : '未配置' }}
            </el-tag>
            <el-button link type="primary" @click="replacement.keyCertPwd = !replacement.keyCertPwd">
              {{ replacement.keyCertPwd ? '取消替换' : '替换证书密码' }}
            </el-button>
          </template>
          <el-input v-if="formType === 'create' || replacement.keyCertPwd" v-model="formData.keyCertPwd"
            type="password" autocomplete="new-password" placeholder="输入新凭据；留空保持服务器原值" />
        </div>
      </el-form-item>
      <el-form-item label="异步回调地址" prop="notifyUrl">
        <el-input v-model="formData.notifyUrl" placeholder="请输入异步回调" />
      </el-form-item>
      <el-form-item label="同步回调地址" prop="returnUrl">
        <el-input v-model="formData.returnUrl" placeholder="请输入同步回调地址，大部分用于付款成功后页面转跳" />
      </el-form-item>
      <el-form-item label="签名方式" prop="signType">
        <el-select v-model="formData.signType" placeholder="请选择签名方式MD5,RSA等等">
          <el-option label="RSA" value="RSA" />
          <el-option label="RSA2" value="RSA2" />
          <el-option label="MD5" value="MD5" />
        </el-select>
      </el-form-item>
      <el-form-item label="子appid" prop="subAppId">
        <el-input v-model="formData.subAppId" placeholder="请输入子appid" />
      </el-form-item>
      <el-form-item label="子商户id" prop="subMchId">
        <el-input v-model="formData.subMchId" placeholder="请输入子商户id" />
      </el-form-item>
      <el-form-item label="是否为测试环境" prop="isTest">
        <el-radio-group v-model="formData.isTest">
          <el-radio :label="1">是</el-radio>
          <el-radio :label="0">否</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="submitForm" type="primary" :disabled="formLoading">确 定</el-button>
      <el-button @click="dialogVisible = false">取 消</el-button>
    </template>
  </Dialog>
</template>
<script setup lang="ts">
import * as MerchantDetailsApi from '@/api/pay/merchantDetails'

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗


const dialogVisible = ref(false) // 弹窗的是否展示
const dialogTitle = ref('') // 弹窗的标题
const formLoading = ref(false) // 表单的加载中：1）修改时的数据加载；2）提交的按钮禁用
const formType = ref('') // 表单的类型：create - 新增；update - 修改
const credentialStatus = reactive({
  privateKeyConfigured: false,
  certificatePasswordConfigured: false,
  keyCertificateConfigured: false
})
const replacement = reactive({ keyPrivate: false, keyCert: false, keyCertPwd: false })
const formData = ref<MerchantDetailsApi.MerchantDetailsWriteVO>({
  detailsId: undefined,
  payType: undefined,
  appid: undefined,
  mchId: undefined,
  certStoreType: undefined,
  keyPrivate: undefined,
  keyPublic: undefined,
  keyCert: undefined,
  keyCertPwd: undefined,
  notifyUrl: undefined,
  returnUrl: undefined,
  signType: undefined,
  seller: undefined,
  subAppId: undefined,
  subMchId: undefined,
  inputCharset: undefined,
  isTest: undefined as number | undefined
})
const formRules = reactive({
  payType: [{ required: true, message: '支付类型(支付渠道)不能为空', trigger: 'change' }],
  detailsId: [{ required: true, message: '支付id不能为空', trigger: 'change' }],
  appid: [{ required: true, message: '应用id不能为空', trigger: 'change' }],
  signType: [{ required: true, message: '签名方式不能为空', trigger: 'change' }],
  notifyUrl: [{ required: true, message: '异步回调地址不能为空', trigger: 'blur' }]
})
const formRef = ref() // 表单 Ref

/** 打开弹窗 */
const open = async (type: string, id?: string) => {
  dialogVisible.value = true
  dialogTitle.value = t('action.' + type)
  formType.value = type
  resetForm()
  // 修改时，设置数据
  if (id) {
    formLoading.value = true
    try {
      const { privateKeyConfigured, certificatePasswordConfigured, keyCertificateConfigured, ...metadata } =
        await MerchantDetailsApi.getMerchantDetails(id) as MerchantDetailsApi.MerchantDetailsVO
      Object.assign(credentialStatus, { privateKeyConfigured, certificatePasswordConfigured, keyCertificateConfigured })
      formData.value = { ...metadata }
    } finally {
      formLoading.value = false
    }
  }
}
defineExpose({ open }) // 提供 open 方法，用于打开弹窗

/** 提交表单 */
const emit = defineEmits(['success']) // 定义 success 事件，用于操作成功后的回调
const submitForm = async () => {
  // 校验表单
  if (!formRef) return
  const valid = await formRef.value.validate()
  if (!valid) return
  // 提交请求
  formLoading.value = true
  try {
    const data: MerchantDetailsApi.MerchantDetailsWriteVO = { ...formData.value }
    for (const field of ['keyPrivate', 'keyCert', 'keyCertPwd'] as const) {
      if ((formType.value === 'update' && !replacement[field]) || !data[field]?.trim()) {
        delete data[field]
      }
    }
    if (formType.value === 'create') {
      await MerchantDetailsApi.createMerchantDetails(data)
      message.success(t('common.createSuccess'))
    } else {
      await MerchantDetailsApi.updateMerchantDetails(data)
      message.success(t('common.updateSuccess'))
    }
    dialogVisible.value = false
    // 发送操作成功的事件
    emit('success')
  } finally {
    formLoading.value = false
  }
}

/** 重置表单 */
const resetForm = () => {
  Object.assign(credentialStatus, { privateKeyConfigured: false, certificatePasswordConfigured: false, keyCertificateConfigured: false })
  Object.assign(replacement, { keyPrivate: false, keyCert: false, keyCertPwd: false })
  formData.value = {
    detailsId: undefined,
    payType: undefined,
    appid: undefined,
    mchId: undefined,
    certStoreType: undefined,
    keyPrivate: undefined,
    keyPublic: undefined,
    keyCert: undefined,
    keyCertPwd: undefined,
    notifyUrl: undefined,
    returnUrl: undefined,
    signType: undefined,
    seller: undefined,
    subAppId: undefined,
    subMchId: undefined,
    inputCharset: undefined,
    isTest: 1
  }
  formRef.value?.resetFields()
}
</script>
