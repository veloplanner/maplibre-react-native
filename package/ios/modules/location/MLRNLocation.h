#import <CoreLocation/CoreLocation.h>

@interface MLRNLocation : NSObject

@property (nonatomic, strong) CLLocation *location;

- (NSDictionary<NSString *, id> *)toJSON;

@end
