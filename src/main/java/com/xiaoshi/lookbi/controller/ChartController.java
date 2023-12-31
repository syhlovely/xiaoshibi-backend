package com.xiaoshi.lookbi.controller;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.xiaoshi.lookbi.annotation.AuthCheck;
import com.xiaoshi.lookbi.constant.CommonConstant;
import com.xiaoshi.lookbi.constant.UserConstant;
import com.xiaoshi.lookbi.exception.BusinessException;
import com.xiaoshi.lookbi.exception.ThrowUtils;
import com.xiaoshi.lookbi.message.RabbitMqMessageProducer;
import com.xiaoshi.lookbi.model.dto.chart.*;
import com.xiaoshi.lookbi.cache.Cache;
import com.xiaoshi.lookbi.common.BaseResponse;
import com.xiaoshi.lookbi.common.DeleteRequest;
import com.xiaoshi.lookbi.common.ErrorCode;
import com.xiaoshi.lookbi.common.ResultUtils;
import com.xiaoshi.lookbi.config.RabbitMQConfig;
import com.xiaoshi.lookbi.manager.AiManager;
import com.xiaoshi.lookbi.manager.RedisLimiterManager;
import com.xiaoshi.lookbi.model.entity.Chart;
import com.xiaoshi.lookbi.model.entity.User;
import com.xiaoshi.lookbi.model.vo.AiResponse;
import com.xiaoshi.lookbi.model.vo.BiVo;
import com.xiaoshi.lookbi.service.ChartService;
import com.xiaoshi.lookbi.service.UserService;
import com.xiaoshi.lookbi.utils.ExcelUtil;
import com.xiaoshi.lookbi.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 帖子接口
 *
 */
@RestController
@RequestMapping("/chart")
@Slf4j
public class ChartController {

    @Resource
    private ChartService chartService;

    @Resource
    private UserService userService;

    private final static Gson GSON = new Gson();

    @Resource
    private AiManager aiManager;

    @Resource
    private RedisLimiterManager redisLimiterManager;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private RabbitMqMessageProducer rabbitMqMessageProducer;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


/**
 * 你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：
 * 分析需求： {数据分析的需求或者目标}
 * 原始数据： {csv格式的原始数据，用,作为分隔符}
 * 请根据这两部分内容，按照以下指定格式生成内容
 * （此外不要输出任何多余的开头、结尾、注释
 * 【【【【【 {前端 Echarts V5 的 option 配置对象js代码，合理地将数据进行可视化，不要生成任何多
 * 【【【【【
 * {明确的数据分析结论、越详细越好，不要生成多余的注释}
 */


    /**
     * 智能分析（同步）
     *
     * @param multipartFile
     * @param
     * @param request
     * @return
     */
    @PostMapping("/gen")
    public BaseResponse<BiVo> genChartByAi(@RequestPart("file") MultipartFile multipartFile,
                                           genChartByAiRequest genChartByAiRequest, HttpServletRequest request) {

        Long userId = userService.getLoginUser(request).getId();

        //限流,限流粒度：按照传的key值来，这里按照用户和方法进行限流
        redisLimiterManager.doRateLimit("genChartByAi_" + userId);

        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100 ,ErrorCode.PARAMS_ERROR,"名字格式错误");
        ThrowUtils.throwIf(StringUtils.isBlank(goal),ErrorCode.PARAMS_ERROR,"分析参数为空");
        //校验文件大小以及文件名称后缀是否合法
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        final long file_MB = 1024 * 1024l;
        ThrowUtils.throwIf(size > file_MB,ErrorCode.PARAMS_ERROR,"文件过大");
        //校验名称后缀是否合法
        List<String> nameList = Arrays.asList("jpg", "png", "svg", "jpeg", "webp", "xlsx", "xls");
        //获取文件名后缀进行过滤
        String suffix = FileUtil.getSuffix(originalFilename);
        ThrowUtils.throwIf(!nameList.contains(suffix),ErrorCode.NOT_FOUND_ERROR,"文件类型错误");


        String csv = ExcelUtil.excelToCsv(multipartFile);
        StringBuilder msg = new StringBuilder();
        //拼接要发送的消息
        if (StringUtils.isNotBlank(chartType)){
            goal += ",请使用: " + chartType;
         }
        msg.append("分析需求: \n").append(goal).append("\n");
        msg.append("原始数据: \n").append(csv);

        //预设已经有了
        //通过yupisdk发请求获取返回值
        String doChat = aiManager.doChat(CommonConstant.AI_ID, msg.toString());
        System.out.println(doChat);
        //分割返回的字符串获取我们需要的数据
        String[] split = doChat.split("【【【【【");
        if (split.length<2){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"AI生成错误");
        }
        String echarts = split[1];
        String info = split[2];
        //保存数据到数据库当中
        Chart chart = new Chart();
        chart.setGoal(goal);
        chart.setName(name);
        chart.setChartData(csv);
        chart.setChartType(chartType);
        chart.setGenChart(echarts);
        chart.setGenResult(info);
        chart.setUserId(userId);
        chartService.save(chart);



        //返回数据
        BiVo biVo = new BiVo();
        biVo.setGenChart(echarts);
        biVo.setGenResult(info);
        biVo.setChartId(chart.getId());

        return ResultUtils.success(biVo);

    }

    /**
     * 智能分析（异步）
     *
     * @param multipartFile
     * @param
     * @param request
     * @return
     */
    @PostMapping("/gen/async")
    public BaseResponse<BiVo> genChartByAiAsync(@RequestPart("file") MultipartFile multipartFile,
                                           genChartByAiRequest genChartByAiRequest, HttpServletRequest request) {

        Long userId = userService.getLoginUser(request).getId();

        //限流,限流粒度：按照传的key值来，这里按照用户和方法进行限流
        redisLimiterManager.doRateLimit("genChartByAi_" + userId);

        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100 ,ErrorCode.PARAMS_ERROR,"名字格式错误");
        ThrowUtils.throwIf(StringUtils.isBlank(goal),ErrorCode.PARAMS_ERROR,"分析参数为空");
        //校验文件大小以及文件名称后缀是否合法
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        final long file_MB = 1024 * 1024l;
        ThrowUtils.throwIf(size > file_MB,ErrorCode.PARAMS_ERROR,"文件过大");
        //校验名称后缀是否合法
        List<String> nameList = Arrays.asList("jpg", "png", "svg", "jpeg", "webp", "xlsx", "xls");
        //获取文件名后缀进行过滤
        String suffix = FileUtil.getSuffix(originalFilename);
        ThrowUtils.throwIf(!nameList.contains(suffix),ErrorCode.NOT_FOUND_ERROR,"文件类型错误");

        //excel转换成csv
        String csv = ExcelUtil.excelToCsv(multipartFile);
        StringBuilder msg = new StringBuilder();
        //拼接要发送的消息
        if (StringUtils.isNotBlank(chartType)){
            goal += ",请使用: " + chartType;
        }
        msg.append("分析需求: \n").append(goal).append("\n");
        msg.append("原始数据: \n").append(csv);

        //保存数据到数据库当中，更改数据库状态
        Chart chart = new Chart();
        chart.setGoal(goal);
        chart.setName(name);
        chart.setChartData(csv);
        chart.setChartType(chartType);
        //图表信息放到线程池中进行更新
//        chart.setGenChart(echarts);
//        chart.setGenResult(info);
        chart.setUserId(userId);
        //将图表生成状态改为等待生成
        chart.setChartStatus("wait");
        chartService.save(chart);

        CompletableFuture.runAsync(()->{
            //先将图表信息状态改成执行中
            boolean update = chartService.updateById(Chart.builder().id(chart.getId()).chartStatus("running").build());
            if (!update){
                handleChartUpdateError(chart.getId(),"更新图表信息失败");
                return;
            }
            //预设已经有了,异步处理图表请求
            //通过yupisdk发请求获取返回值
            String doChat = aiManager.doChat(CommonConstant.AI_ID, msg.toString());
            System.out.println(doChat);
            //分割返回的字符串获取我们需要的数据
            String[] split = doChat.split("【【【【【");
            if (split.length<3){
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,"AI生成错误,请重新生成一次");
            }
            //生成的echarts代码
            String echarts = split[1];
            //生成的图表分析结论
            String info = split[2];
            //更新图表信息，更新状态为生成图表成功，succeed
            boolean succeed = chartService.updateById(Chart.builder().genChart(echarts).genResult(info).chartStatus("succeed").id(chart.getId()).build());
            if (!succeed){
                handleChartUpdateError(chart.getId(),"更新图表信息失败");
                return;
            }
        }, threadPoolExecutor);
        //返回数据，不用返回图表数据了，后续查看在图表查看中
        BiVo biVo = new BiVo();
//        biVo.setGenChart(echarts);
//        biVo.setGenResult(info);
        biVo.setChartId(chart.getId());
        return ResultUtils.success(biVo);
    }

    /**
     * 智能分析（消息队列）
     *
     * @param multipartFile
     * @param
     * @param request
     * @return
     */
    @PostMapping("/gen/asyncMq")
    public BaseResponse<BiVo> genChartByAiMqAsync(@RequestPart("file") MultipartFile multipartFile,
                                                genChartByAiRequest genChartByAiRequest, HttpServletRequest request) {


        Long userId = userService.getLoginUser(request).getId();

        //下面的限流实现的是同一时间段的限流，这里实现的是防止单个用户进行刷量
        String key = "genChart:" + userId;
        //查看redis中的ttl，如果有ttl的话证明还在限制条件之内
        Long expire = stringRedisTemplate.getExpire(key);
        log.info("当前用户剩余限制时间ttl:{}",expire);
//        ThrowUtils.throwIf(expire >= 0,ErrorCode.PARAMS_ERROR,"速度太快,请稍后再重试");
        if (expire >= 0){
            throw new RuntimeException("速度太快，请稍后再试");
//            return ResultUtils.error(ErrorCode.TOO_MANY_REQUEST);
        }
        //一个用户同时智能生成一个图表，这里用redis的setex实现，5秒内只能生成一张

        stringRedisTemplate.opsForValue().set(key,userId.toString(),5, TimeUnit.SECONDS);

        //限流,限流粒度：按照传的key值来，这里按照用户和方法进行限流
        redisLimiterManager.doRateLimit("genChartByAi_" + userId);

        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100 ,ErrorCode.PARAMS_ERROR,"名字格式错误");
        ThrowUtils.throwIf(StringUtils.isBlank(goal),ErrorCode.PARAMS_ERROR,"分析参数为空");
        //校验文件大小以及文件名称后缀是否合法
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        final long file_MB = 1024 * 1024l;
        ThrowUtils.throwIf(size > file_MB,ErrorCode.PARAMS_ERROR,"文件过大");
        //校验名称后缀是否合法
        List<String> nameList = Arrays.asList("jpg", "png", "svg", "jpeg", "webp", "xlsx", "xls");
        //获取文件名后缀进行过滤
        String suffix = FileUtil.getSuffix(originalFilename);
        ThrowUtils.throwIf(!nameList.contains(suffix),ErrorCode.NOT_FOUND_ERROR,"文件类型错误");

        //excel转换成csv
        String csv = ExcelUtil.excelToCsv(multipartFile);

        //保存数据到数据库当中，更改数据库状态
        Chart chart = Chart.builder()
                .goal(goal)
                .name(name)
                .chartData(csv)
                .chartType(chartType)
                .userId(userId)
                .chartStatus("wait")
                .build();
        chartService.save(chart);

        //消息消息队列发送消息服务
        rabbitMqMessageProducer.sendMessage(RabbitMQConfig.EXCHANGE,RabbitMQConfig.ROUTING_KEY,chart.getId().toString());


        //返回数据，不用返回图表数据了，后续查看在图表查看中
        BiVo biVo = new BiVo();
//        biVo.setGenChart(echarts);
//        biVo.setGenResult(info);
        biVo.setChartId(chart.getId());
        return ResultUtils.success(biVo);
    }

    /**
     * 图表更新错误
     *
     * @param chartId
     * @param execMessage
     */
    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chartId);
        updateChartResult.setChartStatus("failed");
        updateChartResult.setExecMessage("图表更新失败！！");
        boolean updateResult = chartService.updateById(updateChartResult);
        if (!updateResult) {
            log.error("更新图表失败状态失败" + chartId + "," + execMessage);
        }
    }



    // region 增删改查

    /**
     * 创建
     *
     * @param chartAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addChart(@RequestBody ChartAddRequest chartAddRequest, HttpServletRequest request) {
        if (chartAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartAddRequest, chart);
        User loginUser = userService.getLoginUser(request);
        chart.setUserId(loginUser.getId());
        boolean result = chartService.save(chart);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        long newChartId = chart.getId();
        return ResultUtils.success(newChartId);
    }

    /**
     * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteChart(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldChart.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = chartService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新（仅管理员）
     *
     * @param chartUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateChart(@RequestBody ChartUpdateRequest chartUpdateRequest) {
        if (chartUpdateRequest == null || chartUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartUpdateRequest, chart);
        long id = chartUpdateRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = chartService.updateById(chart);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<Chart> getChartById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = chartService.getById(id);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(chart);
    }

    /**
     * 分页获取列表（封装类）
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Chart>> listChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
            HttpServletRequest request) {
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    /**
     * 分页获取当前用户创建的资源列表
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @Cache
    @PostMapping("/my/list/page")
    public BaseResponse<Page<Chart>> listMyChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
            HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    // endregion

    /**
     * 编辑（用户）
     *
     * @param chartEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editChart(@RequestBody ChartEditRequest chartEditRequest, HttpServletRequest request) {
        if (chartEditRequest == null || chartEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartEditRequest, chart);
        User loginUser = userService.getLoginUser(request);
        long id = chartEditRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        if (!oldChart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = chartService.updateById(chart);
        return ResultUtils.success(result);
    }

    /**
     * 获取查询包装类
     *
     * @param chartQueryRequest
     * @return
     */
    private QueryWrapper<Chart> getQueryWrapper(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        if (chartQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chartQueryRequest.getId();
        String name = chartQueryRequest.getName();
        String goal = chartQueryRequest.getGoal();
        String chartType = chartQueryRequest.getChartType();
        Long userId = chartQueryRequest.getUserId();
        String sortField = chartQueryRequest.getSortField();
        String sortOrder = chartQueryRequest.getSortOrder();

        queryWrapper.eq(id != null && id > 0, "id", id);
        queryWrapper.like(StringUtils.isNotBlank(name),"name",name);
        queryWrapper.eq(StringUtils.isNotBlank(goal), "goal", goal);
        queryWrapper.eq(StringUtils.isNotBlank(chartType), "chartType", chartType);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @GetMapping("get/vo")
    public BaseResponse<Chart> getChartVOById(@RequestParam("id") Long id){
        Chart chart = chartService.getById(id);
        return ResultUtils.success(chart);
    }


    /**
     * 图表重新生成(mq)
     *
     * @param chartRebuildRequest
     * @param request
     * @return
     */
    @PostMapping("/gen/async/rebuild")
    public BaseResponse<AiResponse> genChartAsyncAiRebuild(ChartRebuildRequest chartRebuildRequest, HttpServletRequest request) {
        Long chartId = chartRebuildRequest.getId();
        Chart genChartByAiRequest = chartService.getById(chartId);
        String chartType = genChartByAiRequest.getChartType();
        String goal = genChartByAiRequest.getGoal();
        String name = genChartByAiRequest.getName();
        String chartData = genChartByAiRequest.getChartData();

        //校验
        ThrowUtils.throwIf(StringUtils.isBlank(goal),ErrorCode.PARAMS_ERROR,"目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name)&&name.length()>=100,ErrorCode.PARAMS_ERROR,"名称过长");
        ThrowUtils.throwIf(StringUtils.isBlank(chartData),ErrorCode.PARAMS_ERROR,"表格数据为空");
        ThrowUtils.throwIf(StringUtils.isBlank(chartType),ErrorCode.PARAMS_ERROR,"生成表格类型为空");

        User loginUser = userService.getLoginUser(request);
        //限流
        redisLimiterManager.doRateLimit("doRateLimit_" + loginUser.getId());

        //保存数据库 wait
        Chart chart = new Chart();
        chart.setChartStatus("wait");
        chart.setId(chartId);
        boolean saveResult = chartService.updateById(chart);
        ThrowUtils.throwIf(!saveResult,ErrorCode.SYSTEM_ERROR,"图表保存失败");
        log.warn("准备发送信息给队列，Message={}=======================================",chartId);
        rabbitMqMessageProducer.sendMessage(RabbitMQConfig.EXCHANGE,RabbitMQConfig.ROUTING_KEY,chartId.toString());
        //返回数据参数
        AiResponse aiResponse = new AiResponse();
        aiResponse.setResultId(chart.getId());
        return ResultUtils.success(aiResponse);

    }


}
