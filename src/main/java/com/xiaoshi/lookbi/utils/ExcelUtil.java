package com.xiaoshi.lookbi.utils;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author shiyinghan
 * @Date 2023/7/29 18:02
 * @PackageName:com.yupi.springbootinit.utils
 * @ClassName: Excel转csv
 * @Description: TODO
 * @Version 1.0
 */
@Slf4j
public class ExcelUtil {

        public static String excelToCsv(MultipartFile multipartFile) {
            StringBuilder stringBuilder = new StringBuilder();
//            File file = null;
//            try {
//                file = ResourceUtils.getFile("classpath:test_excel.xlsx");
//            } catch (FileNotFoundException e) {
//                e.printStackTrace();
//            }
            List<Map<Integer, String>> list = null;
            try {
                list = EasyExcel.read(multipartFile.getInputStream())
                        .excelType(ExcelTypeEnum.XLSX)
                        .sheet()
                        .headRowNumber(0)
                        .doReadSync();
            } catch (IOException e) {
                log.error("excel转Csv异常",e);
            }
            //处理数据，把数据转换为csv格式
            //先取出表头
            LinkedHashMap<Integer,String> headerMap = (LinkedHashMap)list.get(0);
            //做去空值校验
            List<String> collect = headerMap.values().stream().filter(ObjectUtil::isNotEmpty).collect(Collectors.toList());
            //用字符串工具类中的join分割字符串
            stringBuilder.append(StringUtils.join(collect,",")).append("\n");
            //取出数据
            for (int i = 1; i < list.size(); i++) {
                //做去空值校验,用逗号拼接
                String value = StringUtils.join(list.get(i).values().stream().filter(ObjectUtil::isNotEmpty).collect(Collectors.toList()), ",");
                stringBuilder.append(StringUtils.join(value,",")).append("\n");
            }
            return stringBuilder.toString();
        }

    public static void main(String[] args) {
        System.out.println(excelToCsv(null));
    }

}
