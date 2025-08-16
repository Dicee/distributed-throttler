FROM public.ecr.aws/lambda/java:21-x86_64

COPY assets/classes/kotlin/main ${LAMBDA_TASK_ROOT}
COPY assets/lib ${LAMBDA_TASK_ROOT}/lib

CMD ["com.dici.distributedThrottler.lambda.Handler::handleRequest"]
