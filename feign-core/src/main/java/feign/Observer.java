package feign;

public interface Observer<T> {
  void onNext(T element);

  void onSuccess();

  void onFailure(Throwable cause);
}
