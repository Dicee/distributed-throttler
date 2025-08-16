FROM public.ecr.aws/lambda/java:21-x86_64
WORKDIR /app

COPY assets/ ${LAMBDA_TASK_ROOT}

CMD ["com.dici.distributedThrottler.lambda.Handler::handleRequest"]
