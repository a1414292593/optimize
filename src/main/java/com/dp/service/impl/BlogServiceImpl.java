package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dp.dto.Result;
import com.dp.dto.ScrollResult;
import com.dp.dto.UserDTO;
import com.dp.entity.Blog;
import com.dp.entity.Follow;
import com.dp.entity.User;
import com.dp.mapper.BlogMapper;
import com.dp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.service.IFollowService;
import com.dp.service.IUserService;
import com.dp.utils.SystemConstants;
import com.dp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *


 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IFollowService followService;

    @Resource
    IBlogService blogService;

    @Override
    public Result queryBlogById(Long id) {
        //根据Id查询博客
        Blog blog = getById(id);
        if (blog == null) {
           return Result.fail("博客不存在!");
        }
        //封装博客信息
        queryBlogUser(blog);
        //判断是否点赞
        isBlogLiked(blog);
        //返回
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录
            return;
        }
        //判断当前用户是否点赞过
        Long userId = user.getId();
        Long id = blog.getId();
        String likedKey = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(likedKey, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User writer = userService.getById(userId);
        blog.setIcon(writer.getIcon());
        blog.setName(writer.getNickName());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String likedKey = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(likedKey, userId.toString());
        // 3.未点赞
        if (score == null) {
            // 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 保存用户到redis集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(likedKey, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.已点赞
            // 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 把用户从redis集合中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(likedKey, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 1.查询Top5的用户
        String likedKey = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(likedKey, 0, 4);
        if (top5 == null ||  top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户Id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.根据Id查询用户
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        // 4.返回
        return Result.ok(users);
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        //  1.根据用户查询
        Page<Blog> page = query().eq("user_id", id).page(new Page<Blog>(current, SystemConstants.MAX_PAGE_SIZE));
        // 2.获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("笔记保存失败");
        }
        // 3.查询笔记作者的所有粉丝
        Long userId = UserHolder.getUser().getId();
        // 4.推送笔记Id给所有粉丝
        List<Long> followUserId = followService.query()
                .eq("follow_user_id", userId).list()
                .stream().map(Follow::getUserId)
                .collect(Collectors.toList());
        for (Long id : followUserId) {
            //推送
            String key = FEED_KEY + id;
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(blog.getId()), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱
        String key = FEED_KEY + userId;
        // 3.解析数据: blogId,miniTime(时间戳),offset
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, FEED_COUNT);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.根据blogId查询blog
        long minTime = 0;
        int os = 1;
        List<Long> ids = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取时间戳
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = blogService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        // 5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
